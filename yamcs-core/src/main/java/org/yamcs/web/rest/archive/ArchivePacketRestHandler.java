package org.yamcs.web.rest.archive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import org.yamcs.web.rest.RestStreamSubscriber;
import org.yamcs.web.rest.RestStreams;
import org.yamcs.web.rest.Route;
import org.yamcs.web.rest.SqlBuilder;
import org.yamcs.yarch.Stream;
import org.yamcs.yarch.Tuple;

public class ArchivePacketRestHandler extends RestHandler {

    private static final Logger log = LoggerFactory.getLogger(ArchivePacketRestHandler.class);

    @Route(path = "/api/archive/:instance/packets/:gentime?", method = "GET")
    public void listPackets(RestRequest req) throws HttpException {
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
            /* TODO FIXME
            ByteBuf buf = req.getChannelHandlerContext().alloc().buffer();
            try (ByteBufOutputStream bufOut = new ByteBufOutputStream(buf)) {
                RestStreams.runAsync(instance, sqlb.toString(), new RestStreamSubscriber(pos, limit) {

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
                }).thenRun(() -> {
                    try {
                        bufOut.close();
                    } catch()
                    sendOK(req, MediaType.OCTET_STREAM, buf);
                });
            } catch (IOException e) {
                throw new InternalServerErrorException(e);
            }*/
        } else {
            ListPacketsResponse.Builder responseb = ListPacketsResponse.newBuilder();
            RestStreams.runAsync(instance, sqlb.toString(), new RestStreamSubscriber(pos, limit) {

                @Override
                public void processTuple(Stream stream, Tuple tuple) {
                    TmPacketData pdata = GPBHelper.tupleToTmPacketData(tuple);
                    responseb.addPacket(pdata);
                }
            }).thenRun(() -> sendOK(req, responseb.build(), SchemaRest.ListPacketsResponse.WRITE));
        }
    }

    @Route(path = "/api/archive/:instance/packets/:gentime/:seqnum", method = "GET")
    public void getPacket(RestRequest req) throws HttpException {
        String instance = verifyInstance(req, req.getRouteParam("instance"));
        long gentime = req.getDateRouteParam("gentime");
        int seqNum = req.getIntegerRouteParam("seqnum");

        SqlBuilder sqlb = new SqlBuilder(XtceTmRecorder.TABLE_NAME)
                .where("gentime = " + gentime, "seqNum = " + seqNum);

        List<TmPacketData> packets = new ArrayList<>();
        RestStreams.runAsync(instance, sqlb.toString(), new RestStreamSubscriber(0, 2) {

            @Override
            public void processTuple(Stream stream, Tuple tuple) {
                TmPacketData pdata = GPBHelper.tupleToTmPacketData(tuple);
                packets.add(pdata);
            }
        }).thenRun(() -> {
            if (packets.isEmpty()) {
                sendRestError(req, new NotFoundException(req, "No packet for id (" + gentime + ", " + seqNum + ")"));
            } else if (packets.size() > 1) {
                sendRestError(req, new InternalServerErrorException("Too many results"));
            } else {
                sendOK(req, packets.get(0), SchemaYamcs.TmPacketData.WRITE);
            }
        });
    }
}
