package org.yamcs.web.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.YProcessor;
import org.yamcs.YamcsException;
import org.yamcs.management.ManagementService;
import org.yamcs.protobuf.Rest.RestListProcessorsResponse;
import org.yamcs.protobuf.SchemaRest;
import org.yamcs.protobuf.SchemaYamcsManagement;
import org.yamcs.protobuf.YamcsManagement.ProcessorManagementRequest;
import org.yamcs.protobuf.YamcsManagement.ProcessorRequest;

/**
 * /(instance)/api/processor
 */
public class ProcessorRequestHandler implements RestRequestHandler {
    private static final Logger log = LoggerFactory.getLogger(ProcessorRequestHandler.class.getName());
    
    @Override
    public RestResponse handleRequest(RestRequest req, int pathOffset) throws RestException {
        if (!req.hasPathSegment(pathOffset)) {
            return handleProcessorManagementRequest(req);
        }
        
        switch (req.getPathSegment(pathOffset)) {
        case "list": // TODO move this as a GET of 'processors' without the 'list' stuff
            return handleProcessorListRequest(req);
            
        default:
            String processorName = null;
            if (req.hasPathSegment(pathOffset + 1)) {
                processorName = req.getPathSegment(pathOffset + 1);
            }
            if (processorName==null) {
                log.warn("Sending NOT_FOUND because invalid processor name '{}' has been requested", processorName);
                throw new NotFoundException(req);
            }
            
            YProcessor processor = YProcessor.getInstance(req.yamcsInstance, processorName);
            return handleProcessorRequest(req, processor);
        }
    }

    private RestResponse handleProcessorListRequest(RestRequest req) throws RestException {
        req.assertGET();
        RestListProcessorsResponse.Builder response = RestListProcessorsResponse.newBuilder();
        for (YProcessor processor : YProcessor.getChannels()) {
            response.addProcessor(ManagementService.getProcessorInfo(processor));
        }
        return new RestResponse(req, response.build(), SchemaRest.RestListProcessorsResponse.WRITE);
    }
        
    private RestResponse handleProcessorRequest(RestRequest req, YProcessor yproc) throws RestException {
        req.assertPOST();
        ProcessorRequest yprocReq = req.bodyAsMessage(SchemaYamcsManagement.ProcessorRequest.MERGE).build();
        switch(yprocReq.getOperation()) {
        case RESUME:
            if(!yproc.isReplay()) {
                throw new BadRequestException("Cannot resume a non replay processor ");
            } 
            yproc.resume();
            break;
        case PAUSE:
            if(!yproc.isReplay()) {
                throw new BadRequestException("Cannot pause a non replay processor ");
            }
            yproc.pause();
            break;
        case SEEK:
            if(!yproc.isReplay()) {
                throw new BadRequestException("Cannot seek a non replay processor ");
            }
            if(!yprocReq.hasSeekTime()) {
                throw new BadRequestException("No seek time specified");                
            }
            yproc.seek(yprocReq.getSeekTime());
            break;
        default:
            throw new BadRequestException("Invalid operation "+yprocReq.getOperation()+" specified");
        }
        return new RestResponse(req);
    }
    
    private RestResponse handleProcessorManagementRequest(RestRequest req) throws RestException {
        req.assertPOST();
        ProcessorManagementRequest yprocReq = req.bodyAsMessage(SchemaYamcsManagement.ProcessorManagementRequest.MERGE).build();
        switch(yprocReq.getOperation()) {
        case CONNECT_TO_PROCESSOR:
            ManagementService mservice = ManagementService.getInstance();
            try {
                mservice.connectToProcessor(yprocReq, req.authToken);
                return new RestResponse(req);
            } catch (YamcsException e) {
                throw new BadRequestException(e.getMessage());
            }
        
        case CREATE_PROCESSOR:
            mservice = ManagementService.getInstance();
            try {
                mservice.createProcessor(yprocReq, req.authToken);
                return new RestResponse(req);
            } catch (YamcsException e) {
                throw new BadRequestException(e.getMessage());
            }
        
        default:
            throw new BadRequestException("Invalid operation "+yprocReq.getOperation()+" specified");
        }
    }
}
