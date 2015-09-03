package org.yamcs.tctm;


import java.util.ArrayList;
import java.util.HashSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.YProcessor;
import org.yamcs.YProcessorException;
import org.yamcs.ConfigurationException;
import org.yamcs.InvalidIdentification;
import org.yamcs.ParameterValue;
import org.yamcs.TmProcessor;
import org.yamcs.YamcsException;
import org.yamcs.YamcsServer;
import org.yamcs.archive.PacketWithTime;
import org.yamcs.archive.ReplayListener;
import org.yamcs.archive.ReplayServer;
import org.yamcs.archive.YarchReplay;
import org.yamcs.parameter.ParameterProvider;
import org.yamcs.parameter.ParameterRequestManager;
import org.yamcs.protobuf.Pvalue.ParameterData;
import org.yamcs.protobuf.Yamcs.EndAction;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.protobuf.Yamcs.ProtoDataType;
import org.yamcs.protobuf.Yamcs.ReplayRequest;
import org.yamcs.protobuf.Yamcs.ReplaySpeed;
import org.yamcs.protobuf.Yamcs.ReplayStatus;
import org.yamcs.protobuf.Yamcs.ReplayStatus.ReplayState;
import org.yamcs.protobuf.Yamcs.TmPacketData;
import org.yamcs.security.SystemToken;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.SystemParameterDb;
import org.yamcs.xtce.XtceDb;
import org.yamcs.xtceproc.XtceDbFactory;

import com.google.common.util.concurrent.AbstractService;
import com.google.protobuf.MessageLite;


/**
 * Provides telemetry packets and processed parameters from the yamcs archive.
 * 
 * @author nm
 * 
 */
public class ReplayService extends AbstractService implements ReplayListener, ArchiveTmPacketProvider, ParameterProvider {
    static final long timeout=10000;

    boolean loop;
    long start, stop; // start and stop times of playback request
    String[] packets; // array of opsnames of packets to subscribe
    EndAction endAction;
    static Logger log=LoggerFactory.getLogger(ReplayService.class.getName());

    ReplayRequest replayRequest;
    private HashSet<Parameter> subscribedParameters=new HashSet<Parameter>();
    private ParameterRequestManager parameterRequestManager;
    TmProcessor tmProcessor;
    volatile long dataCount=0;
    final XtceDb xtceDb;
    volatile long replayTime;

    private final String yamcsInstance;
    YarchReplay yarchReplay;
    YProcessor yprocessor;
    
   
    public ReplayService(String instance, ReplayRequest spec) throws YProcessorException, ConfigurationException {
        this.yamcsInstance = instance;
        this.replayRequest = spec;
        xtceDb = XtceDbFactory.getInstance(instance);
        createReplay();
    }


    private void createReplay() throws YProcessorException {
        ReplayServer replayServer = YamcsServer.getService(yamcsInstance, ReplayServer.class);
        if(replayServer==null) {
            throw new YProcessorException("ReplayServer not configured for this instance");
        }
        try {
            yarchReplay = replayServer.createReplay(replayRequest, this, new SystemToken());
        } catch (YamcsException e) {
            log.error("Exception creating the replay", e);
            throw new YProcessorException("Exception creating the replay",e);
        }
    }
    
    
    @Override
    public void init(YProcessor proc) throws ConfigurationException {
        this.yprocessor = proc;
    }

    @Override
    public void init(YProcessor proc, TmProcessor tmProcessor) {
        this.tmProcessor=tmProcessor;
        this.yprocessor = proc;
    }

    @Override
    public boolean isArchiveReplay() {
        return true;
    }
    
    @Override
    public void newData(ProtoDataType type, MessageLite data) {
      //  System.out.println("ReplayService new Data received: "+data);
        switch(type) {
        case TM_PACKET:
            dataCount++;
            TmPacketData tpd=(TmPacketData)data;
            replayTime = tpd.getGenerationTime();
            tmProcessor.processPacket(new PacketWithTime(tpd.getReceptionTime(), tpd.getGenerationTime(), tpd.getPacket().toByteArray()));
            break;
        case PP:
            //convert from protobuf ParameterValue to internal ParameterValue 
            ParameterData pd=(ParameterData)data;
            ArrayList<ParameterValue> params=new ArrayList<ParameterValue>(pd.getParameterCount());
            for(org.yamcs.protobuf.Pvalue.ParameterValue pbPv:pd.getParameterList()) {
                Parameter ppDef = xtceDb.getParameter(pbPv.getId());
                ParameterValue pv = ParameterValue.fromGpb(ppDef, pbPv);
                if(pv!=null) {
                    params.add(pv);
                    replayTime = pv.getGenerationTime();
                }
                
            }
            parameterRequestManager.update(params);
            break;
          default:
                log.error("Unexpected data type {} received");            
        }
        
    }

    @Override
    public void stateChanged(ReplayStatus rs) {
        if(rs.getState()==ReplayState.CLOSED) {
            log.debug("End signal received");
            notifyStopped();
            tmProcessor.finished();
        } else {
            yprocessor.notifyStateChange();
        }
    }


    @Override
    public void doStop() {
        if(yarchReplay!=null) {
            yarchReplay.quit();
        }
        notifyStopped();
    }


    @Override
    public void doStart() {
        yarchReplay.start();
        notifyStarted();
    }

    @Override
    public void pause() {
        yarchReplay.pause();
    }

    @Override
    public void resume() {
        yarchReplay.start();
    }


    @Override
    public void seek(long time) {
        try {
            yarchReplay.seek(time);
        } catch (YamcsException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public void setParameterListener(ParameterRequestManager parameterRequestManager) {
        this.parameterRequestManager = parameterRequestManager;
    }

    @Override
    public void startProviding(Parameter paramDef) {
        synchronized(subscribedParameters) {
            subscribedParameters.add(paramDef);
        }
    }

    @Override
    public void startProvidingAll() {
        //TODO
    }


    @Override
    public void stopProviding(Parameter paramDef) {
        synchronized(subscribedParameters) {
            subscribedParameters.remove(paramDef);
        }
    }

    @Override
    public boolean canProvide(NamedObjectId id) {
        boolean result = false;
        if(xtceDb.getParameter(id)!=null) {
            result=true;
        } else { //check if it's system parameter
           if(SystemParameterDb.isSystemParameter(id)) {
               result = true;
           }
        }
        return result;
    }

    @Override
    public boolean canProvide(Parameter p) {
        return true;
    }

    @Override
    public Parameter getParameter(NamedObjectId id) throws InvalidIdentification {
        Parameter p = xtceDb.getParameter(id);
        if(p==null) {
            if(SystemParameterDb.isSystemParameter(id)) {
                p = xtceDb.getSystemParameterDb().getSystemParameter(id);
            }
            
        }
        if(p==null) {
            throw new InvalidIdentification();
        } else {
            return p;
        }
    }

    @Override
    public ReplaySpeed getSpeed() {
        return replayRequest.getSpeed();
    }

    @Override
    public ReplayRequest getReplayRequest() {
        if(yarchReplay!=null) {
            return yarchReplay.getCurrentReplayRequest();
        } else {
            return replayRequest;
        }
    }

    @Override
    public ReplayState getReplayState() {
        if(!isRunning()) {
            return ReplayState.CLOSED;
        }
        return yarchReplay.getState();
    }

    @Override
    public long getReplayTime() {
        return replayTime;
    }


    @Override
    public void changeSpeed(ReplaySpeed speed) {
       yarchReplay.changeSpeed(speed);        
    }
}