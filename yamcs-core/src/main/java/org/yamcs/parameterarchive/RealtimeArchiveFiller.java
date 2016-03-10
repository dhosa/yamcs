package org.yamcs.parameterarchive;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.ConfigurationException;
import org.yamcs.ContainerExtractionResult;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.YConfiguration;
import org.yamcs.YProcessor;

public class RealtimeArchiveFiller extends ArchiveFillerTask {
    ScheduledThreadPoolExecutor executor=new ScheduledThreadPoolExecutor(1);
    int flushInterval = 300; //seconds
    protected Logger log = LoggerFactory.getLogger(this.getClass());
    String processorName = "realtime";
    final String yamcsInstance;
    
    public RealtimeArchiveFiller(ParameterArchive parameterArchive, Map<String, Object> config) {
        super(parameterArchive);
        this.yamcsInstance = parameterArchive.getYamcsInstance();
        if(config!=null) {
            parseConfig(config);
        }
    }
    
    private void parseConfig(Map<String, Object> config) {
        flushInterval = YConfiguration.getInt(config, "flushInterval", flushInterval);      
        processorName = YConfiguration.getString(config, "processorName", processorName);
    }
    
    
    
    //move the updateItems (and all processing thereafter) in the executor thread
    @Override
    public void updateItems(int subscriptionId, List<ContainerExtractionResult> containers, List<ParameterValue> items) {
        executor.execute(()-> {
            try {
                super.updateItems(subscriptionId, containers, items);
            } catch(Exception e) {
                log.error("Error when adding data to realtime segments", e);
            }
        });
    }
    
    public void flush() {
        try {
            for(Map.Entry<Long, Map<Integer, PGSegment>> k:pgSegments.entrySet()) {
                Map <Integer, PGSegment> m = k.getValue();
                long sstart = k.getKey();
                log.debug("Writing to archive the segment: [{} - {})", TimeEncoding.toString(sstart), TimeEncoding.toString(SortedTimeSegment.getNextSegmentStart(sstart)));
                consolidateAndWriteToArchive(m.values());
            }
        } catch(Exception e) {
            log.error("Error when flusing data to parameter archive ", e);
        }
    }
    
    
    void start() {
        //subscribe to the realtime processor
        YProcessor yprocessor = YProcessor.getInstance(yamcsInstance, processorName);
        if(yprocessor == null) {
            throw new ConfigurationException("No processor named '"+processorName+"' in instance "+yamcsInstance);
        }
        yprocessor.getParameterRequestManager().subscribeAll(this);
        executor.scheduleAtFixedRate(this::flush, flushInterval, flushInterval, TimeUnit.SECONDS);
        
    }
    
    void stop() {
        executor.shutdown();
        flush();
    }
}
