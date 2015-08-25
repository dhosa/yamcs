package org.yamcs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.cmdhistory.CommandHistoryRequestManager;
import org.yamcs.cmdhistory.YarchCommandHistoryAdapter;
import org.yamcs.commanding.CommandQueueManager;
import org.yamcs.commanding.CommandReleaser;
import org.yamcs.commanding.CommandingManager;
import org.yamcs.container.ContainerRequestManager;
import org.yamcs.management.ManagementService;
import org.yamcs.parameter.ParameterProvider;
import org.yamcs.parameter.ParameterRequestManagerImpl;
import org.yamcs.protobuf.Yamcs.ReplayRequest;
import org.yamcs.protobuf.Yamcs.ReplayStatus.ReplayState;
import org.yamcs.protobuf.YamcsManagement.ServiceState;
import org.yamcs.tctm.ArchiveTmPacketProvider;
import org.yamcs.tctm.TcTmService;
import org.yamcs.time.TimeService;
import org.yamcs.xtce.XtceDb;
import org.yamcs.xtceproc.XtceDbFactory;
import org.yamcs.xtceproc.XtceTmProcessor;

import com.google.common.util.concurrent.AbstractService;


/**
 * This class helps keeping track of the different objects used in a Yamcs Processor - i.e. all the 
 *  objects required to have a TM/TC processing chain (either realtime or playback).
 *
 * There are two ways in which parameter and packet delivery is performed:
 *  asynchronous - In this mode the parameters are put into a queue in order to not block the processing thread. The queue
 *                is flushed by the deliver method called from the sessionImpl own thread. This is the mode normally used 
 *                for realtime and playbacks at close to realtime speed.
 *  synchronous -  In this mode the parameter are delivered in the processing queue, blocking thus the extraction if the client
 *                is slow. This is the mode used in the as fast as possible retrievals. 
 *
 *  The synchronous/asynchronous logic is implemented in the TelemetryImpl and TelemetryPacketImpl classes
 * @author mache
 *
 */
public class YProcessor extends AbstractService {
    static private Map<String,YProcessor>instances=Collections.synchronizedMap(new HashMap<String,YProcessor>());
    private ParameterRequestManagerImpl parameterRequestManager;
    private ContainerRequestManager containerRequestManager;
    private CommandHistoryPublisher commandHistoryPublisher;

    private CommandHistoryRequestManager commandHistoryRequestManager;

    private CommandingManager commandingManager;


    private TmPacketProvider tmPacketProvider;
    private CommandReleaser commandReleaser;
    private List<ParameterProvider> parameterProviders = new ArrayList<ParameterProvider>();

    private XtceDb xtcedb;

    private String name;
    private String type;
    private final String yamcsInstance;

    private boolean checkAlarms = true;
    private boolean alarmServerEnabled = false;

    private String creator="system";
    private boolean persistent=false;

    private boolean parameterCacheEnabled = false;
    private boolean parameterCacheAll = false;

    static Logger log=LoggerFactory.getLogger(YProcessor.class.getName());
    static Set<YProcessorListener> listeners=new CopyOnWriteArraySet<>(); //send notifications for added and removed processors to this

    private boolean quitting;
    //a synchronous channel waits for all the clients to deliver tm packets and parameters
    private boolean synchronous=false;
    XtceTmProcessor tmProcessor;

    //unless very good performance reasons, we should try to serialize all the processing in this thread
    final private ExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    final private ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);

    @GuardedBy("this")
    HashSet<YProcessorClient> connectedClients= new HashSet<YProcessorClient>();

    public YProcessor(String yamcsInstance, String name, String type, String creator) throws YProcessorException {
        if((name==null) || "".equals(name)) {
            throw new YProcessorException("The channel name can not be empty");
        }
        log.info("creating a new channel name="+name+" type="+type);
        this.yamcsInstance=yamcsInstance;
        this.name=name;
        this.creator=creator;
        this.type=type;
    }



    @SuppressWarnings("unchecked")
    void init(TcTmService tctms, Map<String, Object> config) throws YProcessorException, ConfigurationException {
        xtcedb=XtceDbFactory.getInstance(yamcsInstance);

        synchronized(instances) {
            if(instances.containsKey(key(yamcsInstance,name))) throw new YProcessorException("A channel named '"+name+"' already exists in instance "+yamcsInstance);
            if(config!=null) {
                for(String c: config.keySet()) {
                    if("alarm".equals(c)) {
                        Object o = config.get(c);
                        if(!(o instanceof Map)) {
                            throw new ConfigurationException("alarm configuration should be a map");
                        }
                        configureAlarms((Map<String, Object>) o);
                    } else if("parameterCache".equals(c)) {
                        Object o = config.get(c);
                        if(!(o instanceof Map)) {
                            throw new ConfigurationException("parameterCache configuration should be a map");
                        }
                        configureParameterCache((Map<String, Object>) o);
                    }else {
                        log.warn("Ignoring unknown config key '"+c+"'");
                    }
                }
            }


            this.tmPacketProvider=tctms.getTmPacketProvider();
            this.commandReleaser=tctms.getCommandReleaser();
            List<ParameterProvider> providers = tctms.getParameterProviders();
            if(providers!=null) {
                this.parameterProviders.addAll(providers);
            }

            synchronous = tctms.isSynchronous();

            
            // Shared between prm and crm
            tmProcessor = new XtceTmProcessor(this);
            if(tmPacketProvider!=null) {
            	tmPacketProvider.setTmProcessor(tmProcessor);
            }
            containerRequestManager=new ContainerRequestManager(this, tmProcessor);
            parameterRequestManager=new ParameterRequestManagerImpl(this, tmProcessor);

            //    containerRequestManager.setPacketProvider(tmPacketProvider);

            for(ParameterProvider pprov: parameterProviders) {
                pprov.init(this);
                parameterRequestManager.addParameterProvider(pprov);
            }

            if(commandReleaser!=null) {
                try {
                    this.commandHistoryPublisher=new YarchCommandHistoryAdapter(yamcsInstance);
                } catch (Exception e) {
                    throw new ConfigurationException("Cannot create command history" , e);
                }
                commandingManager=new CommandingManager(this);
                commandReleaser.setCommandHistory(commandHistoryPublisher);
                commandHistoryRequestManager = new CommandHistoryRequestManager(yamcsInstance);
            } else {
                commandingManager=null;
            }


            instances.put(key(yamcsInstance,name),this);
            listeners.forEach(l -> l.processorAdded(this));
            ManagementService.getInstance().registerYProcessor(this);
        }
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    private void configureAlarms(Map<String, Object> alarmConfig) {
        Object v = alarmConfig.get("check");
        if(v!=null) {
            if(!(v instanceof Boolean)) {
                throw new ConfigurationException("Unknwon value '"+v+"' for alarmConfig -> check. Boolean expected.");
            }
            checkAlarms = (Boolean)v;
        }

        v = alarmConfig.get("server");
        if(v!=null) {
            if(!(v instanceof String)) {
                throw new ConfigurationException("Unknwon value '"+v+"' for alarmConfig -> server. String expected.");

            }
            alarmServerEnabled = "enabled".equalsIgnoreCase((String)v);
            if(alarmServerEnabled) checkAlarms=true;
        }
    }

    private void configureParameterCache(Map<String, Object> cacheConfig) {
        Object v = cacheConfig.get("enabled");
        if(v!=null) {
            if(!(v instanceof Boolean)) {
                throw new ConfigurationException("Unknwon value '"+v+"' for parameterCache -> enabled. Boolean expected.");
            }
            parameterCacheEnabled = (Boolean)v;
        }

        v = cacheConfig.get("cacheAll");
        if(v!=null) {
            if(!(v instanceof Boolean)) {
                throw new ConfigurationException("Unknwon value '"+v+"' for parameterCache -> cacheAll. Boolean expected.");
            }
            parameterCacheAll = (Boolean)v;
            if(parameterCacheAll) parameterCacheEnabled=true;
        }
    }

    private static String key(String instance, String name) {
        return instance+"."+name;
    }

    public CommandHistoryPublisher getCommandHistoryPublisher() {
        return commandHistoryPublisher;
    }

    public ParameterRequestManagerImpl getParameterRequestManager() {
        return parameterRequestManager;
    }

    public ContainerRequestManager getContainerRequestManager() {
        return containerRequestManager;
    }

    public XtceTmProcessor getTmProcessor() {
        return tmProcessor;
    }


    /**
     * starts processing by invoking the start method for all the associated processors
     *
     */
    @Override
    public void doStart() {
    	if(tmPacketProvider!=null) {
    		tmPacketProvider.startAsync();
    	}
    	
        if(commandReleaser!=null) {
            commandReleaser.startAsync();
            commandReleaser.awaitRunning();
            commandHistoryRequestManager.startAsync();
            commandingManager.startAsync();
            commandingManager.awaitRunning();
            CommandQueueManager cqm = commandingManager.getCommandQueueManager();
            cqm.startAsync();
            cqm.awaitRunning();
        }
        for(ParameterProvider pprov: parameterProviders) {
            pprov.startAsync();
        }

        parameterRequestManager.start();
        
        if(tmPacketProvider!=null) {
        	tmPacketProvider.awaitRunning();
        }
        notifyStarted();
        propagateChannelStateChange();
    }
    

    public void pause() {
        ((ArchiveTmPacketProvider)tmPacketProvider).pause();
        propagateChannelStateChange();
    }

    public void resume() {
        ((ArchiveTmPacketProvider)tmPacketProvider).resume();
        propagateChannelStateChange();
    }

    private void propagateChannelStateChange() {
        listeners.forEach(l -> l.processorStateChanged(this));
    }
    public void seek(long instant) {
        getTmProcessor().resetStatistics();
        ((ArchiveTmPacketProvider)tmPacketProvider).seek(instant);
    }

    /**
     * @return the tcUplinker
     */
    public CommandReleaser getCommandReleaser() {
        return commandReleaser;
    }

    /**
     * @return the tmPacketProvider
     */
    public TmPacketProvider getTmPacketProvider() {
        return tmPacketProvider;
    }

    public String getName() {
        return name;
    }

    /**
     * @return the type
     */
    public String getType() {
        return type;
    }

    public String getCreator() {
        return creator;
    }


    public void setCreator(String creator) {
        this.creator = creator;
    }


    public int getConnectedClients() {
        return connectedClients.size();
    }

    public static YProcessor getInstance(String yamcsInstance, String name) {
        return instances.get(key(yamcsInstance,name));
    }
    /**
     * Increase with one the number of connected clients to the named channel and return the channel.
     * @param name
     * @return the channel where with the given name
     * @throws YProcessorException
     */
    public static YProcessor connect(String yamcsInstance, String name, YProcessorClient s) throws YProcessorException {
        YProcessor ds=instances.get(key(yamcsInstance,name));
        if(ds==null) throw new YProcessorException("There is no channel named '"+name+"'");
        ds.connect(s);
        return ds;
    }

    /**
     * Increase with one the number of connected clients
     */
    public synchronized void connect(YProcessorClient s) throws YProcessorException {
        log.debug("Session "+name+" has one more user: " +s);
        if(quitting) throw new YProcessorException("This channel has been closed");
        connectedClients.add(s);
    }

    /**
     * Disconnects a client from this channel. If the channel has no more clients, quit.
     *
     */
    public void disconnect(YProcessorClient s) {
        if(quitting) return;
        boolean hasToQuit=false;
        synchronized(this) {
            connectedClients.remove(s);
            log.info("channel "+name+" has one less user: connectedUsers: "+connectedClients.size());
            if((connectedClients.isEmpty())&&(!persistent)) {
                hasToQuit=true;
            }
        }
        if(hasToQuit) stopAsync();
    }


    public static Collection<YProcessor> getChannels() {
        return instances.values();
    }


    /**
     * Closes the channel by stoping the tm/pp and tc
     * It can be that there are still clients connected, but they will not get any data and new clients can not connect to
     * these channels anymore. Once it is close, you can create a channel with the same name which will make it maybe a bit 
     * confusing :(
     *
     */
    public void doStop() {
        if(quitting)return;
        log.info("Channel "+name+" quitting");
        quitting=true;
        instances.remove(key(yamcsInstance,name));
        for(ParameterProvider p:parameterProviders) {
            p.stopAsync();
        }
        //if(commandHistoryListener!=null) commandHistoryListener.channelStopped();
        if(commandReleaser!=null) commandReleaser.stopAsync();
        log.info("Channel "+name+" is out of business");
        listeners.forEach(l -> l.yProcessorClosed(this));
        ManagementService.getInstance().unregisterYProcessor(this);
        synchronized(this) {
            for(YProcessorClient s:connectedClients) {
                s.yProcessorQuit();
            }
        }
        notifyStopped();
    }


    public static void addProcessorListener(YProcessorListener channelListener) {
        listeners.add(channelListener);
    }
    public static void removeYProcListener(YProcessorListener channelListener) {
        listeners.remove(channelListener);
    }

    public boolean isPersistent() {
        return persistent;
    }

    public void setPersistent(boolean systemSession) {
        this.persistent = systemSession;
    }

    public boolean isSynchronous() {
        return synchronous;
    }

    public boolean hasCommanding() {
        return (commandingManager!=null);
    }

    public void setSynchronous(boolean synchronous) {
        this.synchronous = synchronous;
    }

    public boolean isReplay() {
        if(tmPacketProvider==null) return false;
        
        return tmPacketProvider.isArchiveReplay();
    }

    /**
     * valid only if isArchiveReplay returns true
     * @return
     */
    public ReplayRequest getReplayRequest() {
        return ((ArchiveTmPacketProvider)tmPacketProvider).getReplayRequest();
    }

    /**
     * valid only if isArchiveReplay returns true
     * @return
     */
    public ReplayState getReplayState() {
        return ((ArchiveTmPacketProvider)tmPacketProvider).getReplayState();
    }

    public ServiceState getState() {
        return ServiceState.valueOf(state().name());
    }

    public CommandingManager getCommandingManager() {
        return commandingManager;
    }

    @Override
    public String toString() {
        return "name: "+name+" type: "+type+" connectedClients:"+connectedClients.size();
    }

    /**
     *
     * @return the yamcs instance this channel is part of
     */
    public String getInstance() {
        return yamcsInstance;
    }

    public XtceDb getXtceDb() {
        return xtcedb;
    }

    public CommandHistoryRequestManager getCommandHistoryManager() {
        return commandHistoryRequestManager;
    }

    public boolean hasAlarmChecker() {
        return checkAlarms;
    }

    public boolean hasAlarmServer() {
        return alarmServerEnabled;
    }

    public boolean isParameterCacheEnabled () {
        return parameterCacheEnabled;
    }

    public boolean cacheAllParameters() {
        return parameterCacheAll;
    }



    public ScheduledThreadPoolExecutor getTimer() {
        return timer;
    }

    /**
     * Returns the processor time
     * 
     *  for realtime processors it is the mission time or simulation time
     *  for replay processors it is the replay time
     * @return 
     */
    public long getCurrentTime() {        
        if(isReplay()) {
            return ((ArchiveTmPacketProvider)tmPacketProvider).lastPacketTime();
        } else {
            TimeService ts = YamcsServer.getInstance(yamcsInstance).getTimeService();
            return ts.getMissionTime();
        }
    }

    public void quit() {
       stopAsync();
       awaitTerminated();
    }



    public void start() {
      startAsync();
      awaitRunning();
    }
}
