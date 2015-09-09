package org.yamcs.api.ws;

import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.protobuf.Websocket.WebSocketServerMessage.WebSocketSubscriptionData;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.util.concurrent.Future;

/**
 * Netty-implementation of a Yamcs web socket client
 */
public class WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClient.class);

    private WebSocketClientCallback callback;
    
    private EventLoopGroup group = new NioEventLoopGroup();
    private URI uri;
    private Channel nettyChannel;
    private String userAgent;
    private AtomicBoolean enableReconnection = new AtomicBoolean(true);
    private AtomicInteger seqId = new AtomicInteger(1);
    private String username;
    private String password;

    // Keeps track of sent subscriptions, so that we can do a resend when we get
    // an InvalidException on some of them :-(
    private Map<Integer, RequestResponsePair> requestResponsePairBySeqId = new ConcurrentHashMap<>();

    public WebSocketClient(YamcsConnectionProperties yprops, WebSocketClientCallback callback) {
        this(yprops, callback, null, null);
    }

    public WebSocketClient(YamcsConnectionProperties yprops, WebSocketClientCallback callback, String username,
            String password) {
        this.uri = yprops.webSocketURI();
        this.callback = callback;
        this.username = username;
        this.password = password;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public void connect() {
        connect(false);
    }

    public void connect(boolean enableReconnection) {
        this.enableReconnection.set(enableReconnection);
        createBootstrap();
    }

    private void createBootstrap() {
        HttpHeaders header = new DefaultHttpHeaders();
        if (userAgent != null) {
            header.add(HttpHeaders.Names.USER_AGENT, userAgent);
        }

        if (username != null) {
            String credentialsClear = username;
            if (password != null)
                credentialsClear += ":" + password;
            String credentialsB64 = new String(Base64.getEncoder().encode(credentialsClear.getBytes()));
            String authorization = "Basic " + credentialsB64;
            header.add(HttpHeaders.Names.AUTHORIZATION, authorization);
        }

        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(uri, WebSocketVersion.V13,
                null, false, header);
        WebSocketClientHandler webSocketHandler = new WebSocketClientHandler(handshaker, this, callback);

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(
                        new HttpClientCodec(),
                        new HttpObjectAggregator(8192),
                        // new WebSocketClientCompressionHandler(),
                        webSocketHandler);
            }
        });

        log.info("WebSocket Client connecting");
        ChannelFuture future = bootstrap.connect(uri.getHost(), uri.getPort());
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    nettyChannel = future.channel();
                } else {
                    if (enableReconnection.get()) {
                        // Set-up reconnection attempts every second
                        // during initial set-up.
                        log.info("reconnect..");
                        group.schedule(() -> createBootstrap(), 1L, TimeUnit.SECONDS);
                    }
                }
            }
        });
    }

    /**
     * Adds said event to the queue. As soon as the web socket is established,
     * queue will be iterated.
     */
    public void sendRequest(WebSocketRequest request) {
        group.execute(() -> doSendRequest(request, null));
    }
    
    public void sendRequest(WebSocketRequest request, WebSocketResponseHandler responseHandler) {
        group.execute(() -> doSendRequest(request, responseHandler));
    }

    /**
     * Really does send the request upstream
     */
    private void doSendRequest(WebSocketRequest request, WebSocketResponseHandler responseHandler) {
        int id = seqId.incrementAndGet();
        requestResponsePairBySeqId.put(id, new RequestResponsePair(request, responseHandler));
        log.debug("Sending request {}", request);
        nettyChannel.writeAndFlush(request.toWebSocketFrame(id));
    }

    RequestResponsePair getRequestResponsePair(int seqId) {
        return requestResponsePairBySeqId.get(seqId);
    }
    
    void forgetUpstreamRequest(int seqId) {
        requestResponsePairBySeqId.remove(seqId);
    }

    boolean isReconnectionEnabled() {
        return enableReconnection.get();
    }

    public void disconnect() {
        enableReconnection.set(false);
        log.info("WebSocket Client sending close");
        nettyChannel.writeAndFlush(new CloseWebSocketFrame());

        // WebSocketClientHandler will close the channel when the server
        // responds to the CloseWebSocketFrame
        nettyChannel.closeFuture().awaitUninterruptibly();
    }

    /**
     * @return the Future which is notified when the executor has been
     *         terminated.
     */
    public Future<?> shutdown() {
        return group.shutdownGracefully();
    }
    
    static class RequestResponsePair {
        WebSocketRequest request;
        WebSocketResponseHandler responseHandler;
        RequestResponsePair(WebSocketRequest request, WebSocketResponseHandler responseHandler) {
            this.request = request;
            this.responseHandler = responseHandler;
        }
    }

    public static void main(String... args) throws InterruptedException {
        YamcsConnectionProperties yprops = new YamcsConnectionProperties("localhost", 8090, "simulator");
        CountDownLatch latch = new CountDownLatch(1);
        WebSocketClient client = new WebSocketClient(yprops, new WebSocketClientCallback() {
            @Override
            public void connected() {
                System.out.println("Connected..........");
                latch.countDown();
            }
            
            @Override
            public void connectionFailed(Throwable t) {
                System.out.println("failed.........."+t.getMessage());
            }
            
            @Override
            public void onMessage(WebSocketSubscriptionData data) {
                System.out.println("Got data " + data);
            }
        });
        
        client.connect();
        latch.await();
        
        client.sendRequest(new WebSocketRequest("time", "subscribe"));
        
        Thread.sleep(5000);
        client.shutdown();
    }
}
