package org.yamcs.web;

import java.util.concurrent.TimeUnit;

import org.yamcs.web.rest.Router;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;


public class HttpServerChannelInitializer extends ChannelInitializer<SocketChannel> {

    private Router apiRouter;
    private WebConfiguration webConfig;

    public HttpServerChannelInitializer(Router apiRouter) {
        this.apiRouter = apiRouter;
        webConfig = WebConfiguration.getInstance();
    }

    @Override
    public void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        if (webConfig.getWebTimeout() > 0) {
            pipeline.addLast(new ReadTimeoutHandler(webConfig.getWebTimeout(), TimeUnit.MILLISECONDS));
        }
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new ChunkedWriteHandler());
        pipeline.addLast(new HttpObjectAggregator(65536));
        pipeline.addLast(new SmartHttpContentCompressor());
        pipeline.addLast(new HttpRequestHandler(apiRouter));
    }
}
