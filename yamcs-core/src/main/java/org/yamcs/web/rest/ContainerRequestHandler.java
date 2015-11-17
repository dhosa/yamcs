package org.yamcs.web.rest;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.InvalidIdentification;
import org.yamcs.NoPermissionException;
import org.yamcs.YProcessor;
import org.yamcs.container.ContainerValueWithId;
import org.yamcs.container.ContainerWithIdConsumer;
import org.yamcs.container.ContainerWithIdRequestHelper;
import org.yamcs.parameter.ParameterRequestManagerImpl;
import org.yamcs.protobuf.Cvalue.ContainerData;
import org.yamcs.protobuf.Cvalue.ContainerValue;
import org.yamcs.protobuf.Rest.BulkGetContainerValueRequest;
import org.yamcs.protobuf.SchemaCvalue;
import org.yamcs.protobuf.SchemaRest;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.XtceDb;

/**
 * Handles incoming requests related to realtime Containers (get).
 * 
 * <p>
 * 
 */
public class ContainerRequestHandler extends RestRequestHandler {
	final static Logger log = LoggerFactory.getLogger(ContainerRequestHandler.class.getName());

	@Override
	public String getPath() {
		return "containers";
	}	
	
	@Override
	public RestResponse handleRequest(RestRequest req, int pathOffset) throws RestException {
        XtceDb mdb = req.getFromContext(MDBRequestHandler.CTX_MDB);
        if (!req.hasPathSegment(pathOffset)) {
            throw new NotFoundException(req);
        } else {
            if ("mget".equals(req.slicePath(pathOffset))) {
                if (req.isGET() || req.isPOST()) {
                    return getContainers(req);
                } else {
                    throw new MethodNotAllowedException(req);
                }
            }  else {
                // Find out if it's a parameter or not. Support any namespace here. Not just XTCE
                NamedObjectId id = null;
                int i = pathOffset + 1;
                for (; i < req.getPathSegmentCount(); i++) {
                    String namespace = req.slicePath(pathOffset, i);
                    String name = req.getPathSegment(i);
                    id = verifyContainerId(mdb, namespace, name);
                    if (id != null) break;
                }
                if (id == null) throw new NotFoundException(req, "Not a valid parameter id");
                Parameter p = mdb.getParameter(id);
                if (p == null) throw new NotFoundException(req, "No parameter for id " + id);
                
                pathOffset = i + 1;
                if (!req.hasPathSegment(pathOffset)) {
                	if (req.isGET()) {
                		return getContainer(req, id);
                	} else {
                		throw new MethodNotAllowedException(req);
                	}
                } else {
                	throw new NotFoundException(req, "No resource '" + req.getPathSegment(pathOffset) + "' for parameter " + id);
                }
            }
        }
	}
	
	
	/**
	 * Get value of a single container
	 * 
	 * @param req
	 * @param id
	 * @return
	 * @throws RestException
	 */
	private RestResponse getContainer(RestRequest req, NamedObjectId id) throws RestException {		
		long timeout = 1000;
		
		if (req.hasQueryParameter("timeout")) { 
			timeout = req.getQueryParameterAsLong("timeout");
		}
		
		List<NamedObjectId> idList = Arrays.asList(id);
		List<ContainerValue> cList = doGetContainerValues(req, idList, timeout);
		
		ContainerValue cValue;
		if (cList.isEmpty()) {
			cValue = ContainerValue.newBuilder().setId(id).build();
		} else {
			cValue = cList.get(0);
		}
		
		ContainerData.Builder cData = ContainerData.newBuilder();
		cData.addContainer(cValue);		
		
		return new RestResponse(req, cData.build(), SchemaCvalue.ContainerData.WRITE);
	}

	/**
	 * Get value of multiple containers
	 */
	private RestResponse getContainers(RestRequest req) throws RestException {
		BulkGetContainerValueRequest request = req.bodyAsMessage(SchemaRest.BulkGetContainerValueRequest.MERGE).build();
		
		if (request.getIdCount() == 0) {
			throw new BadRequestException("Empty parameter list");
		}
		
		long timeout = 1000;
		
		if (req.hasQueryParameter("timeout")) { 
			timeout = req.getQueryParameterAsLong("timeout");
		}
		
		List<NamedObjectId> idList = request.getIdList();
		List<ContainerValue> cList = doGetContainerValues(req, idList, timeout);
		
		ContainerData.Builder cData = ContainerData.newBuilder();
		cData.addAllContainer(cList);

		return new RestResponse(req, cData.build(), SchemaCvalue.ContainerData.WRITE);
	}

	/**
	 * Query the value of containers through the processor
	 * 
	 * @param req
	 * @param idList
	 * @param timeout
	 * @return
	 * @throws RestException
	 */
	private List<ContainerValue> doGetContainerValues(RestRequest req, List<NamedObjectId> idList, long timeout) throws RestException { 
        if (timeout > 60000) {
            throw new BadRequestException("Invalid timeout specified. Maximum is 60.000 milliseconds");
        }
        
        YProcessor processor = req.getFromContext(RestRequest.CTX_PROCESSOR);

		ParameterRequestManagerImpl prm = processor.getParameterRequestManager();
		MyConsumer consumer = new MyConsumer();
		ContainerWithIdRequestHelper cidrh = new ContainerWithIdRequestHelper(prm, consumer);
		
		List<ContainerValue> clist = new LinkedList<>();

		Set<String> remaining = new HashSet<>();
		for (NamedObjectId id : idList) {
			remaining.add(id.getNamespace() + "/" + id.getName());
		}

		try {
			int subscrriptionId = cidrh.subscribeContainers(idList, req.authToken);
			long t0 = System.currentTimeMillis();
			long t1;
			while (true) {
				t1 = System.currentTimeMillis();
				long remainingTime = timeout - (t1 - t0);
				List<ContainerValueWithId> enqueued = consumer.queue.poll(remainingTime, TimeUnit.MILLISECONDS);
				if (enqueued == null) {
					break;
				}

				for (ContainerValueWithId cvid : enqueued) {
					clist.add(cvid.toGbpContainerData());
					remaining.remove(cvid.getId().getNamespace() + "/" + cvid.getId().getName());
				}

				if (remaining.isEmpty()) {
					break;
				}
			}
			cidrh.removeSubscription(subscrriptionId);

		} catch (InvalidIdentification e) {
			throw new BadRequestException("Invalid parameters: " + e.invalidParameters.toString());
		} catch (InterruptedException e) {
			throw new InternalServerErrorException("Interrupted while waiting for containers");
		} catch (NoPermissionException e) {
			throw new ForbiddenException(e.getMessage(), e);
		}

		return clist;				
	}
	
    private NamedObjectId verifyContainerId(XtceDb mdb, String namespace, String name) {
        NamedObjectId id = NamedObjectId.newBuilder().setNamespace(namespace).setName(name).build();
        if (mdb.getParameter(id) != null)
            return id;
        
        String rootedNamespace = "/" + namespace;
        id = NamedObjectId.newBuilder().setNamespace(rootedNamespace).setName(name).build();
        if (mdb.getSequenceContainer(id) != null)
            return id;
        
        return null;
    }
	


	class MyConsumer implements ContainerWithIdConsumer {
		LinkedBlockingQueue<List<ContainerValueWithId>> queue = new LinkedBlockingQueue<>();

		@Override
		public void update(int subscriptionId, List<ContainerValueWithId> containers) {
			queue.add(containers);
		}
	}
}
