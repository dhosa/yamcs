package org.yamcs.yarch.hornet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Random;

import org.hornetq.api.core.HornetQException;
import org.hornetq.api.core.SimpleString;
import org.yamcs.YConfiguration;
import org.yamcs.YamcsServer;

import org.yamcs.api.Protocol;
import org.yamcs.api.YamcsApiException;
import org.yamcs.api.YamcsClient;
import org.yamcs.api.YamcsSession;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.protobuf.Yamcs.Event;
import org.yamcs.protobuf.Yamcs.ProtoDataType;
import org.yamcs.protobuf.Yamcs.Event.EventSeverity;

/**
 * 
 * Sends some events to yarch
 *
 */
public class EventSim {
    YamcsClient msgClient;
    volatile boolean quitting;
    final SimpleString address;
    EventSim(String instance, String host, int port) throws YamcsApiException, HornetQException {
        YamcsSession ys=YamcsSession.newBuilder().setConnectionParams(host,port).build();
        msgClient=ys.newClientBuilder().setDataProducer(true).build();
        address=Protocol.getEventRealtimeAddress(instance);
    }
    
    static String getFortune() {
        try {
            StringBuilder sb=new StringBuilder();
            Process p=Runtime.getRuntime().exec("fortune");
            BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream()));
            char[] chars = new char[1024];
            int read;
            while((read=br.read(chars)) >-1) {
                sb.append(chars,0,read);
            }
            return sb.toString();
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    public void sendEvents() throws HornetQException, IOException, InterruptedException {
        Random random=new Random();
        int i=0;
        while(!quitting) {
            Event event=Event.newBuilder().setGenerationTime(TimeEncoding.getWallclockTime())
                .setSource("EventSim").setSeqNumber(i++)
                .setReceptionTime(TimeEncoding.getWallclockTime())
                .setSeverity(EventSeverity.valueOf(random.nextInt(3)))
                .setType("Fortune").setMessage(getFortune())
                .build();
            msgClient.sendData(address, ProtoDataType.EVENT, event);
            Thread.sleep(1000);
        }
    }

    public void stop() {
        quitting=true;
    }
    
    static void printUsageAndExit() {
        System.err.println("Usage: event-sim.sh [-h host -p port] instance");
        System.exit(-1);
    }
    /**
     * @param args
     * @throws HornetQException 
     * @throws IOException 
     */
    public static void main(String[] args) throws Exception {
    
        int i=0;
        String host="localhost";
        int port=5445;
        String instance=null;
        while(i<args.length) {
            if("-h".equals(args[i])) {
                host=args[++i];
            } else if("-p".equals(args[i])) {
                port=Integer.parseInt(args[++i]);
            } else {
                instance=args[i];
            }
            i++;
        }
        if(instance==null) printUsageAndExit();
        YConfiguration.setup();
        YamcsServer.setupHornet();
        
        TimeEncoding.setUp();
      //  Configuration.setup();
        new EventSim(instance, host, port).sendEvents();
    }
}
