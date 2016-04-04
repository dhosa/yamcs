package org.yamcs.web.rest.archive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.api.MediaType;
import org.yamcs.archive.GPBHelper;
import org.yamcs.archive.XtceTmRecorder;
import org.yamcs.protobuf.Rest.ListPacketsResponse;
import org.yamcs.protobuf.SchemaRest;
import org.yamcs.protobuf.SchemaYamcs;
import org.yamcs.protobuf.Yamcs.TmPacketData;
import org.yamcs.web.HttpException;
import org.yamcs.web.InternalServerErrorException;
import org.yamcs.web.NotFoundException;
import org.yamcs.web.rest.RestHandler;
import org.yamcs.web.rest.RestRequest;
import org.yamcs.web.rest.RestRequest.IntervalResult;
import org.yamcs.web.rest.Route;
import org.yamcs.web.rest.SqlBuilder;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelFuture;

public class ArchivePacketRestHandler extends RestHandler {

    private static final Logger log = LoggerFactory.getLogger(ArchivePacketRestHandler.class);

    @Route(path = "/api/archive/:instance/packets/:gentime?", method = "GET")
    public CompletableFuture<ChannelFuture> listPackets(RestRequest req) throws HttpException {
        String instance = verifyInstance(req, req.getRouteParam("instance"));

        long pos = req.getQueryParameterAsLong("pos", 0);
        int limit = req.getQueryParameterAsInt("limit", 100);

        Set<String> nameSet = new HashSet<>();
        for (String names : req.getQueryParameterList("name", Collections.emptyList())) {
            for (String name : names.split(",")) {
                nameSet.add(name.trim());
            }
        }

        SqlBuilder sqlb = new SqlBuilder(XtceTmRecorder.TABLE_NAME);
        IntervalResult ir = req.scanForInterval();
        if (ir.hasInterval()) {
            sqlb.where(ir.asSqlCondition("gentime"));
        }
        if (req.hasRouteParam("gentime")) {
            sqlb.where("gentime = " + req.getDateRouteParam("gentime"));
        }
        if (!nameSet.isEmpty()) {
            sqlb.where("pname in ('" + String.join("','", nameSet) + "')");
        }
        sqlb.descend(req.asksDescending(true));

        if (req.asksFor(MediaType.OCTET_STREAM)) {

            ByteBuf buf = req.getChannelHandlerContext().alloc().buffer();
            ByteBufOutputStream bufOut = new ByteBufOutputStream(buf);

            FullStreamSupplier supplier = new FullStreamSupplier(instance, sqlb.toString(), pos, limit) {

                @Override
                public void processTuple(Stream stream, Tuple tuple) {
                    TmPacketData pdata = GPBHelper.tupleToTmPacketData(tuple);
                    try {
                        pdata.getPacket().writeTo(bufOut);
                    } catch (IOException e) {
                        log.warn("ignoring packet", e);
                        // should improve to somehow throw upwards
                    }
                }

                @Override
                public ChannelFuture writeFullResponse() throws HttpException {
                    try {
                        bufOut.close();
                        return sendOK(req, MediaType.OCTET_STREAM, buf);
                    } catch(IOException e) {
                        throw new InternalServerErrorException(e);
                    }
                }
            };
            CompletableFuture<ChannelFuture> completableFuture = CompletableFuture.supplyAsync(supplier, yamcsWorkerPool);
            completableFuture.exceptionally(t -> {
                try {
                    bufOut.close();
                    return sendOK(req, MediaType.OCTET_STREAM, buf);
                } catch (IOException e) {
                    log.error("Could not clean up buffer", e);
                }
                return null;
            });
            return completableFuture;
        } else {
            FullStreamSupplier supplier = new FullStreamSupplier(instance, sqlb.toString(), pos, limit) {
                ListPacketsResponse.Builder builder = ListPacketsResponse.newBuilder();

                @Override
                public void processTuple(Stream stream, Tuple tuple) {
                    TmPacketData pdata = GPBHelper.tupleToTmPacketData(tuple);
                    builder.addPacket(pdata);
                }

                @Override
                public ChannelFuture writeFullResponse() throws HttpException {
                    return sendOK(req, builder.build(), SchemaRest.ListPacketsResponse.WRITE);
                }
            };
            return CompletableFuture.supplyAsync(supplier, yamcsWorkerPool);
        }
    }

    @Route(path = "/api/archive/:instance/packets/:gentime/:seqnum", method = "GET")
    public CompletableFuture<ChannelFuture> getPacket(RestRequest req) throws HttpException {
        String instance = verifyInstance(req, req.getRouteParam("instance"));
        long gentime = req.getDateRouteParam("gentime");
        int seqNum = req.getIntegerRouteParam("seqnum");

        SqlBuilder sqlb = new SqlBuilder(XtceTmRecorder.TABLE_NAME)
                .where("gentime = " + gentime, "seqNum = " + seqNum);

        FullStreamSupplier supplier = new FullStreamSupplier(instance, sqlb.toString(), 0, 2) {
            List<TmPacketData> packets = new ArrayList<>();

            @Override
            public void processTuple(Stream stream, Tuple tuple) {
                TmPacketData pdata = GPBHelper.tupleToTmPacketData(tuple);
                packets.add(pdata);
            }

            @Override
            public ChannelFuture writeFullResponse() throws HttpException {
                if (packets.isEmpty()) {
                    return sendRestError(req, new NotFoundException(req, "No packet for id (" + gentime + ", " + seqNum + ")"));
                } else if (packets.size() > 1) {
                    return sendRestError(req, new InternalServerErrorException("Too many results"));
                } else {
                    return sendOK(req, packets.get(0), SchemaYamcs.TmPacketData.WRITE);
                }
            }
        };
        return CompletableFuture.supplyAsync(supplier, yamcsWorkerPool);
    }
}
