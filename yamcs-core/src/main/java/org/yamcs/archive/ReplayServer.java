package org.yamcs.archive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.security.AuthenticationToken;
import org.yamcs.security.HqClientMessageToken;
import org.yamcs.security.Privilege;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.YamcsException;
import org.yamcs.hornetq.HornetQReplayServer;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.protobuf.Yamcs.NamedObjectList;
import org.yamcs.protobuf.Yamcs.PacketReplayRequest;
import org.yamcs.protobuf.Yamcs.ReplayRequest;
import org.yamcs.xtce.MdbMappings;
import org.yamcs.xtceproc.XtceDbFactory;

import com.google.common.util.concurrent.AbstractService;

/**
 *Yarch replay server based on hornetq
 * @author nm
 *
 */
public class ReplayServer extends AbstractService {
    static Logger log=LoggerFactory.getLogger(ReplayServer.class);

    final int MAX_REPLAYS=200;
    final String instance;

    AtomicInteger replayCount=new AtomicInteger();
    HornetQReplayServer hqReplayServer;
    
    public ReplayServer(String instance) {
        this.instance = instance;
    }

    /**
     * create a new packet replay object
     * @throws YamcsException 
     */
    public YarchReplay createReplay(ReplayRequest replayRequest, ReplayListener replayListener, AuthenticationToken authToken) throws YamcsException {
        if(replayCount.get()>=MAX_REPLAYS) {
            throw new YamcsException("maximum number of replays reached");
        }
        log.debug("Creating a replay for time: [{}, {})", TimeEncoding.toString(replayRequest.getStart()), TimeEncoding.toString(replayRequest.getStop()));
        if( Privilege.usePrivileges ) {
            Privilege priv = Privilege.getInstance();

            // Check privileges for requested parameters
            if (replayRequest.hasParameterRequest()) {
                List<NamedObjectId> invalidParameters = new ArrayList<NamedObjectId>();
                for( NamedObjectId noi : replayRequest.getParameterRequest().getNameFilterList() ) {
                    if( ! priv.hasPrivilege(authToken, Privilege.Type.TM_PARAMETER, noi.getName() ) ) {
                        invalidParameters.add( noi );
                    }
                }
                if( ! invalidParameters.isEmpty() ) {
                    NamedObjectList nol=NamedObjectList.newBuilder().addAllList( invalidParameters ).build();
                    log.warn( "Cannot create replay - No privilege for parameters: {}", invalidParameters );
                    throw new YamcsException("InvalidIdentification", "No privilege", nol);
                }
            }

            // Check privileges for requested packets
            // TODO delete right half of if-statement once no longer deprecated
            if (replayRequest.hasPacketRequest()) {
                Collection<String> allowedPackets = priv.getTmPacketNames(instance, authToken, MdbMappings.MDB_OPSNAME);

                List<NamedObjectId> invalidPackets = new ArrayList<NamedObjectId>();
    
                for (NamedObjectId noi : replayRequest.getPacketRequest().getNameFilterList()) {
                    // TODO: fix and not comment
//                    if (! allowedPackets.contains(noi.getName())) {
//                        invalidPackets.add(noi);
//                    }
                }
                if( ! invalidPackets.isEmpty() ) {
                    NamedObjectList nol=NamedObjectList.newBuilder().addAllList( invalidPackets ).build();
                    log.warn( "Cannot create replay - InvalidIdentification for packets: {}", invalidPackets );
                    throw new YamcsException("InvalidIdentification", "Invalid identification", nol);
                }
    
                // Even when no filter is specified, limit request to authorized packets only
                if (replayRequest.getPacketRequest().getNameFilterList().isEmpty()) {
                    PacketReplayRequest.Builder prr = PacketReplayRequest.newBuilder(replayRequest.getPacketRequest());
                    for (String allowedPacket : allowedPackets) {
                        prr.addNameFilter(NamedObjectId.newBuilder().setName(allowedPacket)
                                        .setNamespace(MdbMappings.MDB_OPSNAME));
                    }
                    replayRequest = ReplayRequest.newBuilder(replayRequest).setPacketRequest(prr).build();
                }
            }
        }

        try {
            YarchReplay yr=new YarchReplay(this, replayRequest, replayListener, XtceDbFactory.getInstance(instance), authToken);
            replayCount.incrementAndGet();
            return yr;
        } catch (final Exception e) {
            log.warn("Got exception when creating a replay object: ", e);
            throw new YamcsException("Got exception when creating a replay", e);
        }
    }

    public void replayFinished() {
        replayCount.decrementAndGet();
    }
    @Override
    protected void doStart() {
        try {
            hqReplayServer = new HornetQReplayServer(this);
            hqReplayServer.startAsync();
            hqReplayServer.awaitRunning();
            notifyStarted();
        } catch (Exception e) {
            notifyFailed(e);
        }
    }

    @Override
    public void doStop() {
        hqReplayServer.stopAsync();
        hqReplayServer.awaitTerminated();
        notifyStopped();
    }

    public String getInstance() {
        return instance;
    }
}
