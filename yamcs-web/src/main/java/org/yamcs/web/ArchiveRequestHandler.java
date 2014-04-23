package org.yamcs.web;

import static org.jboss.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.Map;

import org.hornetq.api.core.SimpleString;
import org.hornetq.api.core.client.ClientMessage;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpChunk;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpHeaders.Values;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.YConfiguration;
import org.yamcs.api.Protocol;
import org.yamcs.api.YamcsClient;
import org.yamcs.api.YamcsSession;
import org.yamcs.protobuf.Pvalue.ParameterData;
import org.yamcs.protobuf.Pvalue.ParameterValue;
import org.yamcs.protobuf.Yamcs.EndAction;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.protobuf.Yamcs.PacketReplayRequest;
import org.yamcs.protobuf.Yamcs.ParameterReplayRequest;
import org.yamcs.protobuf.Yamcs.ReplayRequest;
import org.yamcs.protobuf.Yamcs.StringMessage;
import org.yamcs.protobuf.Yamcs.TmPacketData;
import org.yamcs.utils.StringConvertors;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.xtce.MdbMappings;

import com.csvreader.CsvWriter;
import com.google.common.io.Files;

/** 
 * Serves archived data through a web api. The Archived data is fetched from the
 * ReplayServer using HornetQ.
 * <p>
 * Parameters or packets need to be defined in a profile. 
 */
public class ArchiveRequestHandler extends AbstractRequestHandler {
    final static Logger log=LoggerFactory.getLogger(ArchiveRequestHandler.class.getName());
    
    void handleRequest(ChannelHandlerContext ctx, HttpRequest req, MessageEvent evt, String yamcsInstance, String remainingUri) throws Exception {
        if((remainingUri==null) || remainingUri.isEmpty()) {
            sendError(ctx, BAD_REQUEST);
            return;
        }
        
        ReplayRequest.Builder rrb=ReplayRequest.newBuilder()
                        .setEndAction(EndAction.QUIT);
        
        QueryStringDecoder decoder=new QueryStringDecoder(remainingUri);
        Map<String,List<String>> qParams=decoder.getParameters();
        if(qParams.containsKey("start")) {
            rrb.setStart(TimeEncoding.parse(qParams.get("start").get(0)));
        }
        if(qParams.containsKey("stop")) {
            rrb.setStop(TimeEncoding.parse(qParams.get("stop").get(0)));
        }
        if(qParams.containsKey("profile")) {
            if(qParams.get("profile").contains("/")) { // No funny business
                log.warn("Sending BAD_REQUEST because profile contains a /");
                sendError(ctx, BAD_REQUEST);
                return;
            }
            YConfiguration c=YConfiguration.getConfiguration("mdb");
            String dir=c.getGlobalProperty("cacheDirectory");
            File profileDir=new File(dir, "profiles");
            File profile=new File(profileDir, qParams.get("profile").get(0));
            if (profile.exists()) {
                List<String> lines=Files.readLines(profile, CharsetUtil.UTF_8);
                if(decoder.getPath().equals("parameters")) {
                    ParameterReplayRequest.Builder prrb=ParameterReplayRequest.newBuilder();
                    for(String line:lines) {
                        String trimmed=line.trim();
                        if(!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                            if(trimmed.startsWith("/")) {
                                prrb.addNameFilter(NamedObjectId.newBuilder().setName(trimmed));
                            } else {
                                prrb.addNameFilter(NamedObjectId.newBuilder().setNamespace(MdbMappings.MDB_OPSNAME).setName(trimmed));
                            }
                        }
                    }
                    rrb.setParameterRequest(prrb.build());
                } else if(decoder.getPath().equals("packets")) {
                    PacketReplayRequest.Builder prrb=PacketReplayRequest.newBuilder();
                    for(String line:lines) {
                        String trimmed=line.trim();
                        if(!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                            prrb.addNameFilter(NamedObjectId.newBuilder().setName(trimmed));
                        }
                    }
                    rrb.setPacketRequest(prrb.build());
                } else {
                    log.warn("Sending BAD_REQUEST because neither parameter nor packets are requested");
                    sendError(ctx, BAD_REQUEST);
                    return;
                }
            } else {
                log.warn("Sending BAD_REQUEST because neither parameter nor packets are requested");
                sendError(ctx, BAD_REQUEST);
                return;
            }
        } else {
            sendError(ctx, BAD_REQUEST);
            return;
        }

        YamcsSession ys=YamcsSession.newBuilder().setConnectionParams("yamcs://localhost:5445/"+yamcsInstance).build();        
        final YamcsClient msgClient=ys.newClientBuilder().setRpc(true).setDataConsumer(null, null).build();
        try {
            SimpleString replayServer=Protocol.getYarchReplayControlAddress(yamcsInstance);
            StringMessage answer=(StringMessage) msgClient.executeRpc(replayServer, "createReplay", rrb.build(), StringMessage.newBuilder());
            
            // Server is good to go, start the replay
            SimpleString replayAddress=new SimpleString(answer.getMessage());
            msgClient.executeRpc(replayAddress, "start", null, null);
      
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
            response.setChunked(true);
            response.setHeader(Names.TRANSFER_ENCODING, Values.CHUNKED);
            
            if(decoder.getPath().equals("packets")) {
                response.setHeader("Content-Disposition", "attachment; filename=packet-dump"); 
                setContentTypeHeader(response, "application/octet-stream");
            } else if(decoder.getPath().equals("parameters")) {
                response.setHeader("Content-Disposition", "attachment; filename=parameters.csv"); 
                setContentTypeHeader(response, "text/csv");
            }
            
            Channel ch=evt.getChannel();
            ChannelFuture writeFuture=ch.write(response);
            while(true) {
                writeFuture.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if(!future.isSuccess()) {
                            future.getChannel().close();
                            throw new RuntimeException("Exception while writing data to client", future.getCause());
                        }
                    }
                });
                
                ClientMessage msg=msgClient.dataConsumer.receive();
                if(Protocol.endOfStream(msg)) {
                    // Signal end of response
                    ChannelFuture chunkWriteFuture=ch.write(new DefaultHttpChunk(ChannelBuffers.EMPTY_BUFFER));
                    chunkWriteFuture.addListener(ChannelFutureListener.CLOSE);
                    log.trace("All chunks have been written out");
                    break;
                } else {
                    byte[] barray;
                    if(decoder.getPath().equals("packets")) {
                        TmPacketData data=(TmPacketData)Protocol.decode(msg, TmPacketData.newBuilder());
                        barray=data.getPacket().toByteArray();
                    } else if(decoder.getPath().equals("parameters")) {
                        ParameterData pd=(ParameterData)Protocol.decode(msg, ParameterData.newBuilder());
                        ByteArrayOutputStream baos=new ByteArrayOutputStream();
                        CsvWriter csvWriter=new CsvWriter(baos, ';', CharsetUtil.UTF_8);
                        for(ParameterValue pval:pd.getParameterList()) {
                            csvWriter.writeRecord(new String[] {
                                    TimeEncoding.toString(pval.getAcquisitionTime()),
                                    TimeEncoding.toString(pval.getGenerationTime()),
                                    pval.getId().getName(),
                                    StringConvertors.toString(pval.getRawValue(), false),
                                    StringConvertors.toString(pval.getEngValue(), false)
                            });
                        }
                        csvWriter.close();
                        baos.close();
                        barray=baos.toByteArray();
                    } else {
                        throw new IllegalStateException("Unexpected path: "+decoder.getPath());
                    }
                    int n=barray.length;
                    log.trace("Writing chunk of length {}",n);
                    HttpChunk chunk=new DefaultHttpChunk(ChannelBuffers.wrappedBuffer(barray,0,n));
                    writeFuture=ch.write(chunk);
                }
            }
        } finally {
            msgClient.close();
            ys.close();
        }
    }
}
