package org.yamcs.web.rest.archive;

import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.yamcs.web.HttpException;
import org.yamcs.web.InternalServerErrorException;
import org.yamcs.web.rest.RestStreamSubscriber;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.streamsql.ParseException;
import org.yamcs.yarch.streamsql.StreamSqlException;

import io.netty.channel.ChannelFuture;

/**
 * Reads a self-managed stream fully until it closes. Use only for responses you know to be short.
 */
public abstract class FullStreamSupplier extends RestStreamSubscriber implements Supplier<ChannelFuture> {

    private static AtomicInteger streamCounter = new AtomicInteger();

    private Stream stream;
    private Semaphore semaphore = new Semaphore(0);

    public FullStreamSupplier(String instance, String selectSql, long pos, int limit) throws HttpException {
        super(pos, limit);
        stream = prepareStream(instance, selectSql);
        stream.addSubscriber(this);
    }

    @Override
    public ChannelFuture get() {
        try {
            stream.start();
            semaphore.acquire();
            return writeFullResponse();
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    @Override
    public abstract void processTuple(Stream stream, Tuple tuple);

    public abstract ChannelFuture writeFullResponse() throws HttpException;

    @Override
    public void streamClosed(Stream stream) {
        semaphore.release();
    }

    private static Stream prepareStream(String instance, String selectSql) throws HttpException {
        YarchDatabase ydb = YarchDatabase.getInstance(instance);

        String streamName = "rest_archive" + streamCounter.incrementAndGet();
        String sql = new StringBuilder("create stream ")
                .append(streamName)
                .append(" as ")
                .append(selectSql)
                .append(" nofollow")
                .toString();

        try {
            ydb.execute(sql);
        } catch (StreamSqlException | ParseException e) {
            throw new InternalServerErrorException(e);
        }

        Stream stream = ydb.getStream(streamName);
        return stream;
    }
}
