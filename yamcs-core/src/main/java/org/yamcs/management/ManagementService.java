package org.yamcs.management;

import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.ConfigurationException;
import org.yamcs.ProcessorFactory;
import org.yamcs.YProcessor;
import org.yamcs.YProcessorClient;
import org.yamcs.YProcessorException;
import org.yamcs.YProcessorListener;
import org.yamcs.YamcsException;
import org.yamcs.commanding.CommandQueue;
import org.yamcs.commanding.CommandQueueManager;
import org.yamcs.protobuf.YamcsManagement.ClientInfo;
import org.yamcs.protobuf.YamcsManagement.ProcessorInfo;
import org.yamcs.protobuf.YamcsManagement.ProcessorManagementRequest;
import org.yamcs.protobuf.YamcsManagement.Statistics;
import org.yamcs.protobuf.YamcsManagement.TmStatistics;
import org.yamcs.security.AuthenticationToken;
import org.yamcs.security.Privilege;
import org.yamcs.tctm.Link;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.xtceproc.ProcessingStatistics;

import com.google.common.util.concurrent.Service;

/**
 * Responsible for integrating with core yamcs classes, encoding to protobuf,
 * and forwarding aggregated info downstream.
 * <p>
 * Notable examples of downstream listeners are the MBeanServer, the HornetQ-business,
 * and subscribed websocket clients.
 */
public class ManagementService implements YProcessorListener {
    final MBeanServer mbeanServer;
    HornetManagement hornetMgr;
    HornetProcessorManagement hornetProcessorMgr;
    HornetCommandQueueManagement hornetCmdQueueMgr;

    final boolean jmxEnabled, hornetEnabled;
    static Logger log=LoggerFactory.getLogger(ManagementService.class.getName());
    final String tld="yamcs";
    static ManagementService managementService;

    Map<Integer, ClientControlImpl> clients=Collections.synchronizedMap(new HashMap<Integer, ClientControlImpl>());
    AtomicInteger clientId=new AtomicInteger();
    
    // Used to update TM-statistics, and Link State
    ScheduledThreadPoolExecutor timer=new ScheduledThreadPoolExecutor(1);
    
    CopyOnWriteArraySet<ManagementListener> managementListeners = new CopyOnWriteArraySet<>();
    
    Map<YProcessor, Statistics> yprocs=new ConcurrentHashMap<YProcessor, Statistics>();
    static final Statistics STATS_NULL=Statistics.newBuilder().setInstance("null").setYProcessorName("null").build();//we use this one because ConcurrentHashMap does not support null values

    static public void setup(boolean hornetEnabled, boolean jmxEnabled) throws NotCompliantMBeanException, InstanceAlreadyExistsException, MBeanRegistrationException, MalformedObjectNameException, NullPointerException {
        managementService=new ManagementService(hornetEnabled, jmxEnabled);
    }

    static public ManagementService getInstance() {
        return managementService;
    }

    private ManagementService(boolean hornetEnabled, boolean jmxEnabled) throws NotCompliantMBeanException, InstanceAlreadyExistsException, MBeanRegistrationException, MalformedObjectNameException, NullPointerException {
        this.hornetEnabled=hornetEnabled;
        this.jmxEnabled=jmxEnabled;

        if(jmxEnabled)
            mbeanServer=ManagementFactory.getPlatformMBeanServer();
        else
            mbeanServer=null;

        if(hornetEnabled) {
            try {
                hornetMgr=new HornetManagement(this, timer);
                hornetCmdQueueMgr=new HornetCommandQueueManagement();
                hornetProcessorMgr=new HornetProcessorManagement(this);
                addManagementListener(hornetProcessorMgr);
            } catch (Exception e) {
                log.error("failed to start hornet management service: ", e);
                hornetEnabled=false;
                e.printStackTrace();
            }
        }
        
        YProcessor.addProcessorListener(this);
        timer.scheduleAtFixedRate(() -> updateStatistics(), 1, 1, TimeUnit.SECONDS);
    }

    public void shutdown() {
        managementListeners.clear();
        if(hornetEnabled) {
            hornetMgr.stop();
            hornetCmdQueueMgr.stop();
            hornetProcessorMgr.close();
        }
    }

    public void registerService(String instance, String serviceName, Service service) {
        if(jmxEnabled) {
            ServiceControlImpl sci;
            try {
                sci = new ServiceControlImpl(service);
                mbeanServer.registerMBean(sci, ObjectName.getInstance(tld+"."+instance+":type=services,name="+serviceName));
            } catch (Exception e) {
                log.warn("Got exception when registering a service", e);
            }
        }
    }

    public void unregisterService(String instance, String serviceName) {
        if(jmxEnabled) {
            try {
                mbeanServer.unregisterMBean(ObjectName.getInstance(tld+"."+instance+":type=services,name="+serviceName));
            } catch (Exception e) {
                log.warn("Got exception when unregistering a service", e);
            }
        }
    }


    public void registerLink(String instance, String name, String streamName, String spec, Link link) {
        try {
            LinkControlImpl lci = new LinkControlImpl(instance, name, streamName, spec, link);
            if(jmxEnabled) {
                mbeanServer.registerMBean(lci, ObjectName.getInstance(tld+"."+instance+":type=links,name="+name));
            }
            if(hornetEnabled) {
                hornetMgr.registerLink(instance, lci);
            }
        } catch (Exception e) {
            log.warn("Got exception when registering a link: "+e, e);
        }
    }

    public void unregisterLink(String instance, String name) {
        if(jmxEnabled) {
            try {
                mbeanServer.unregisterMBean(ObjectName.getInstance(tld+"."+instance+":type=links,name="+name));
            } catch (Exception e) {
                log.warn("Got exception when unregistering a link", e);
            }
        }

        if(hornetEnabled) {
            hornetMgr.unRegisterLink(instance, name);
        }
    }


    public void registerYProcessor(YProcessor yproc) {
        try {
            YProcessorControlImpl cci = new YProcessorControlImpl(yproc);
            if(jmxEnabled) {
                mbeanServer.registerMBean(cci, ObjectName.getInstance(tld+"."+yproc.getInstance()+":type=processors,name="+yproc.getName()));
            }
        } catch (Exception e) {
            log.warn("Got exception when registering a yprocessor", e);
        }
    }

    public void unregisterYProcessor(YProcessor yproc) {
        if(jmxEnabled) {
            try {
                mbeanServer.unregisterMBean(ObjectName.getInstance(tld+"."+yproc.getInstance()+":type=processors,name="+yproc.getName()));
            } catch (Exception e) {
                log.warn("Got exception when unregistering a yprocessor", e);
            }
        }
    }

    public int registerClient(String instance, String yprocName,  YProcessorClient client) {
        int id=clientId.incrementAndGet();
        try {
            YProcessor c=YProcessor.getInstance(instance, yprocName);
            if(c==null) throw new YamcsException("Unexisting yprocessor ("+instance+", "+yprocName+") specified");
            ClientControlImpl cci = new ClientControlImpl(instance, id, client.getUsername(), client.getApplicationName(), yprocName, client);
            clients.put(cci.getClientInfo().getId(), cci);
            if(jmxEnabled) {
                mbeanServer.registerMBean(cci, ObjectName.getInstance(tld+"."+instance+":type=clients,processor="+yprocName+",id="+id));
            }
            managementListeners.forEach(l -> l.clientRegistered(cci.getClientInfo()));
        } catch (Exception e) {
            log.warn("Got exception when registering a client", e);
        }
        return id;
    }

    public void unregisterClient(int id) {
        ClientControlImpl cci=clients.remove(id);
        if(cci==null) return;
        ClientInfo ci=cci.getClientInfo();
        try {
            if(jmxEnabled) {
                mbeanServer.unregisterMBean(ObjectName.getInstance(tld+"."+ci.getInstance()+":type=clients,processor="+ci.getProcessorName()+",id="+id));
            }
            managementListeners.forEach(l -> l.clientUnregistered(ci));
        } catch (Exception e) {
            log.warn("Got exception when registering a client", e);
        }
    }

    private void switchYProcessor(ClientControlImpl cci, YProcessor yproc, AuthenticationToken authToken) throws YProcessorException {
        ClientInfo oldci=cci.getClientInfo();
        cci.switchYProcessor(yproc, authToken);
        ClientInfo ci=cci.getClientInfo();

        try {
            if(jmxEnabled) {
                mbeanServer.unregisterMBean(ObjectName.getInstance(tld+"."+oldci.getInstance()+":type=clients,processor="+oldci.getProcessorName()+",id="+ci.getId()));
                mbeanServer.registerMBean(cci, ObjectName.getInstance(tld+"."+ci.getInstance()+":type=clients,processor="+ci.getProcessorName()+",id="+ci.getId()));
            }
            managementListeners.forEach(l -> l.clientInfoChanged(ci));
        } catch (Exception e) {
            log.warn("Got exception when switching a processor", e);
        }

    }

    public void createProcessor(ProcessorManagementRequest cr, AuthenticationToken authToken) throws YamcsException{
        log.info("Creating a new yproc instance="+cr.getInstance()+" name="+cr.getName()+" type="+cr.getType()+" spec="+cr.getSpec()+"' persistent="+cr.getPersistent());
        String userName = (authToken != null && authToken.getPrincipal() != null) ? authToken.getPrincipal().toString() : "unknownUser";
        if(!Privilege.getInstance().hasPrivilege(authToken, Privilege.Type.SYSTEM, "MayControlYProcessor")) {
            if(cr.getPersistent()) {
                log.warn("User "+userName+" is not allowed to create persistent yprocessors");
                throw new YamcsException("permission denied");
            }
            if(!"Archive".equals(cr.getType())) {
                log.warn("User "+userName+" is not allowed to create yprocessors of type "+cr.getType());
                throw new YamcsException("permission denied");
            }
            for(int i=0;i<cr.getClientIdCount();i++) {
                ClientInfo si=clients.get(cr.getClientId(i)).getClientInfo();
                if(!userName.equals(si.getUsername())) {
                    log.warn("User "+userName+" is not allowed to connect "+si.getUsername()+" to a new yprocessor "+cr.getName() );
                    throw new YamcsException("permission denied");
                }
            }
        }


        try {
            int n=0;
            YProcessor yproc;
            Object spec;
            if(cr.hasReplaySpec()) {
                spec = cr.getReplaySpec();
            } else {
                spec = cr.getSpec();
            }
            yproc = ProcessorFactory.create(cr.getInstance(), cr.getName(), cr.getType(), userName, spec);
            yproc.setPersistent(cr.getPersistent());
            for(int i=0;i<cr.getClientIdCount();i++) {
                ClientControlImpl cci=clients.get(cr.getClientId(i));
                if(cci!=null) {
                    switchYProcessor(cci, yproc, authToken);
                    n++;
                } else {
                    log.warn("createYProcessor called with invalid client id:"+cr.getClientId(i)+"; ignored.");
                }
            }
            if(n>0 || cr.getPersistent()) {
                log.info("starting new yprocessor'" + yproc.getName() + "' with " + yproc.getConnectedClients() + " clients");
                yproc.start();
            } else {
                yproc.quit();
                throw new YamcsException("createYProcessor invoked with a list full of invalid client ids");
            }
        } catch (YProcessorException e) {
            throw new YamcsException(e.getMessage(), e.getCause());
        } catch (ConfigurationException e) {
            e.printStackTrace();
            throw new YamcsException(e.getMessage(), e.getCause());
        }
    }


    public void connectToProcessor(ProcessorManagementRequest cr, AuthenticationToken usertoken) throws YamcsException {
        YProcessor chan=YProcessor.getInstance(cr.getInstance(), cr.getName());
        if(chan==null) throw new YamcsException("Unexisting yproc ("+cr.getInstance()+", "+cr.getName()+") specified");


        String userName = (usertoken != null && usertoken.getPrincipal() != null) ? usertoken.getPrincipal().toString() : "unknown";
        log.debug("User "+ userName+" wants to connect clients "+cr.getClientIdList()+" to processor "+cr.getName());


        if(!Privilege.getInstance().hasPrivilege(usertoken, Privilege.Type.SYSTEM, "MayControlYProcessor") &&
                !((chan.isPersistent() || chan.getCreator().equals(userName)))) {
            log.warn("User "+userName+" is not allowed to connect users to yproc "+cr.getName() );
            throw new YamcsException("permission denied");
        }
        if(!Privilege.getInstance().hasPrivilege(usertoken, Privilege.Type.SYSTEM, "MayControlYProcessor")) {
            for(int i=0; i<cr.getClientIdCount(); i++) {
                ClientInfo si=clients.get(cr.getClientId(i)).getClientInfo();
                if(!userName.equals(si.getUsername())) {
                    log.warn("User "+userName+" is not allowed to connect "+si.getUsername()+" to yprocessor "+cr.getName());
                    throw new YamcsException("permission denied");
                }
            }
        }

        try {
            for(int i=0;i<cr.getClientIdCount();i++) {
                int id=cr.getClientId(i);
                ClientControlImpl cci=clients.get(id);
                switchYProcessor(cci, chan, usertoken);
            }
        } catch(YProcessorException e) {
            throw new YamcsException(e.toString());
        }
    }

    public void registerCommandQueueManager(String instance, String yprocName, CommandQueueManager cqm) {
        try {
            for(CommandQueue cq:cqm.getQueues()) {
                if(jmxEnabled) {
                    CommandQueueControlImpl cqci = new CommandQueueControlImpl(instance, yprocName, cqm, cq);
                    mbeanServer.registerMBean(cqci, ObjectName.getInstance(tld+"."+instance+":type=commandQueues,processor="+yprocName+",name="+cq.getName()));
                }
            }
            if(hornetEnabled) {
                hornetCmdQueueMgr.registerCommandQueueManager(cqm);
            }
        } catch (Exception e) {
            log.warn("Got exception when registering a command queue", e);
        }
    }
    
    /**
     * Adds a listener that is to be notified when any processor, or any client
     * is updated. Calling this multiple times has no extra effects. Either you
     * listen, or you don't.
     */
    public boolean addManagementListener(ManagementListener l) {
        return managementListeners.add(l);
    }
    
    public boolean removeManagementListener(ManagementListener l) {
        return managementListeners.remove(l);
    }

    public ClientInfo getClientInfo(int clientId) {
        return clients.get(clientId).getClientInfo();
    }
    
    public Set<ClientInfo> getAllClientInfo() {
        synchronized(clients) {
            return clients.values().stream()
                    .map(v -> v.getClientInfo())
                    .collect(Collectors.toSet());
        }
    }
    
    private void updateStatistics() {
        try {
            for(Entry<YProcessor,Statistics> entry:yprocs.entrySet()) {
                YProcessor yproc=entry.getKey();
                Statistics stats=entry.getValue();
                ProcessingStatistics ps=yproc.getTmProcessor().getStatistics();
                if((stats==STATS_NULL) || (ps.getLastUpdated()>stats.getLastUpdated())) {
                    stats=buildStats(yproc);
                    yprocs.put(yproc, stats);
                }
                if(stats!=STATS_NULL) {
                    for (ManagementListener l : managementListeners) {
                        l.statisticsUpdated(yproc, stats);   
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void processorAdded(YProcessor processor) {
        ProcessorInfo pi = getProcessorInfo(processor);
        managementListeners.forEach(l -> l.processorAdded(pi));
        yprocs.put(processor, STATS_NULL);
    }

    @Override
    public void yProcessorClosed(YProcessor processor) {
        ProcessorInfo pi = getProcessorInfo(processor);
        managementListeners.forEach(l -> l.processorClosed(pi));
        yprocs.remove(processor);
    }

    @Override
    public void processorStateChanged(YProcessor processor) {
        ProcessorInfo pi = getProcessorInfo(processor);
        managementListeners.forEach(l -> l.processorStateChanged(pi));
    }
    
    public static Statistics buildStats(YProcessor processor) {
        ProcessingStatistics ps=processor.getTmProcessor().getStatistics();
        Statistics.Builder statsb=Statistics.newBuilder();
        statsb.setLastUpdated(ps.getLastUpdated());
        statsb.setLastUpdatedUTC(TimeEncoding.toString(ps.getLastUpdated()));
        statsb.setInstance(processor.getInstance()).setYProcessorName(processor.getName());
        Collection<ProcessingStatistics.TmStats> tmstats=ps.stats.values();
        if(tmstats==null) {
            return STATS_NULL;
        }

        for(ProcessingStatistics.TmStats t:tmstats) {
            TmStatistics ts=TmStatistics.newBuilder()
                    .setPacketName(t.packetName).setLastPacketTime(t.lastPacketTime)
                    .setLastReceived(t.lastReceived).setReceivedPackets(t.receivedPackets)
                    .setLastPacketTimeUTC(TimeEncoding.toString(t.lastPacketTime))
                    .setLastReceivedUTC(TimeEncoding.toString(t.lastReceived))
                    .setSubscribedParameterCount(t.subscribedParameterCount).build();
            statsb.addTmstats(ts);
        }
        return statsb.build();
    }
    
    public static ProcessorInfo getProcessorInfo(YProcessor yproc) {
        ProcessorInfo.Builder cib=ProcessorInfo.newBuilder().setInstance(yproc.getInstance())
                .setName(yproc.getName()).setType(yproc.getType())
                .setCreator(yproc.getCreator()).setHasCommanding(yproc.hasCommanding())
                .setState(yproc.getState());

        if(yproc.isReplay()) {
            cib.setReplayRequest(yproc.getReplayRequest());
            cib.setReplayState(yproc.getReplayState());
        }
        return cib.build();
    }
}
