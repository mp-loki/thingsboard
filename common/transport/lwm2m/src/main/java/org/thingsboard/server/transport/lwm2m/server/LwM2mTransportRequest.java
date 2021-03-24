/**
 * Copyright © 2016-2021 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.transport.lwm2m.server;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.leshan.core.attributes.Attribute;
import org.eclipse.leshan.core.attributes.AttributeSet;
import org.eclipse.leshan.core.model.ResourceModel;
import org.eclipse.leshan.core.node.LwM2mNode;
import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.core.node.LwM2mSingleResource;
import org.eclipse.leshan.core.node.ObjectLink;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.request.CancelObservationRequest;
import org.eclipse.leshan.core.request.ContentFormat;
import org.eclipse.leshan.core.request.DiscoverRequest;
import org.eclipse.leshan.core.request.DownlinkRequest;
import org.eclipse.leshan.core.request.ExecuteRequest;
import org.eclipse.leshan.core.request.ObserveRequest;
import org.eclipse.leshan.core.request.ReadRequest;
import org.eclipse.leshan.core.request.WriteAttributesRequest;
import org.eclipse.leshan.core.request.WriteRequest;
import org.eclipse.leshan.core.response.CancelObservationResponse;
import org.eclipse.leshan.core.response.DeleteResponse;
import org.eclipse.leshan.core.response.DiscoverResponse;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.LwM2mResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.ResponseCallback;
import org.eclipse.leshan.core.response.WriteAttributesResponse;
import org.eclipse.leshan.core.response.WriteResponse;
import org.eclipse.leshan.core.util.Hex;
import org.eclipse.leshan.core.util.NamedThreadFactory;
import org.eclipse.leshan.server.californium.LeshanServer;
import org.eclipse.leshan.server.registration.Registration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.thingsboard.server.queue.util.TbLwM2mTransportComponent;
import org.thingsboard.server.transport.lwm2m.server.client.LwM2mClient;
import org.thingsboard.server.transport.lwm2m.server.client.LwM2mClientContext;
import org.thingsboard.server.transport.lwm2m.utils.LwM2mValueConverterImpl;

import javax.annotation.PostConstruct;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.eclipse.californium.core.coap.CoAP.ResponseCode.isSuccess;
import static org.eclipse.leshan.core.attributes.Attribute.MINIMUM_PERIOD;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandler.DEFAULT_TIMEOUT;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandler.GET_TYPE_OPER_DISCOVER;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandler.GET_TYPE_OPER_OBSERVE;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandler.GET_TYPE_OPER_READ;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandler.LOG_LW2M_ERROR;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandler.LOG_LW2M_INFO;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandler.POST_TYPE_OPER_EXECUTE;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandler.POST_TYPE_OPER_OBSERVE_CANCEL;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandler.POST_TYPE_OPER_WRITE_REPLACE;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandler.PUT_TYPE_OPER_WRITE_ATTRIBUTES;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandler.PUT_TYPE_OPER_WRITE_UPDATE;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandler.RESPONSE_CHANNEL;

@Slf4j
@Service
@TbLwM2mTransportComponent
public class LwM2mTransportRequest {
    private ExecutorService executorResponse;

    private LwM2mValueConverterImpl converter;

    private final LwM2mTransportContextServer context;

    private final LwM2mClientContext lwM2mClientContext;

    private final LeshanServer leshanServer;

    @Autowired
    private LwM2mTransportServiceImpl serviceImpl;

    public LwM2mTransportRequest(LwM2mTransportContextServer context, LwM2mClientContext lwM2mClientContext, LeshanServer leshanServer) {
        this.context = context;
        this.lwM2mClientContext = lwM2mClientContext;
        this.leshanServer = leshanServer;
    }

    @PostConstruct
    public void init() {
        this.converter = LwM2mValueConverterImpl.getInstance();
        executorResponse = Executors.newFixedThreadPool(this.context.getLwM2MTransportConfigServer().getRequestPoolSize(),
                new NamedThreadFactory(String.format("LwM2M %s channel response", RESPONSE_CHANNEL)));
    }

    /**
     * Device management and service enablement, including Read, Write, Execute, Discover, Create, Delete and Write-Attributes
     *
     * @param registration -
     * @param target -
     * @param typeOper -
     * @param contentFormatParam -
     * @param observation -
     */
    public void sendAllRequest(Registration registration, String target, String typeOper,
                               String contentFormatParam, Observation observation, Object params, long timeoutInMs) {
        LwM2mPath resultIds = new LwM2mPath(target);
        if (registration != null && resultIds.getObjectId() >= 0) {
            DownlinkRequest request = null;
            ContentFormat contentFormat = contentFormatParam != null ? ContentFormat.fromName(contentFormatParam.toUpperCase()) : null;
            ResourceModel resource = serviceImpl.lwM2mTransportContextServer.getLwM2MTransportConfigServer().getResourceModel(registration, resultIds);
            timeoutInMs = timeoutInMs > 0 ? timeoutInMs : DEFAULT_TIMEOUT;
            switch (typeOper) {
                case GET_TYPE_OPER_READ:
                    request = new ReadRequest(contentFormat, target);
                    break;
                case GET_TYPE_OPER_DISCOVER:
                    request = new DiscoverRequest(target);
                    break;
                case GET_TYPE_OPER_OBSERVE:
                    if (resultIds.isResource()) {
                        request = new ObserveRequest(resultIds.getObjectId(), resultIds.getObjectInstanceId(), resultIds.getResourceId());
                    } else if (resultIds.isObjectInstance()) {
                        request = new ObserveRequest(resultIds.getObjectId(), resultIds.getObjectInstanceId());
                    } else if (resultIds.getObjectId() >= 0) {
                        request = new ObserveRequest(resultIds.getObjectId());
                    }
                    break;
                case POST_TYPE_OPER_OBSERVE_CANCEL:
                    request = new CancelObservationRequest(observation);
                    break;
                case POST_TYPE_OPER_EXECUTE:
                    if (params != null && resource != null && !resource.multiple) {
                        request = new ExecuteRequest(target, (String) this.converter.convertValue(params, resource.type, ResourceModel.Type.STRING, resultIds));
                    } else {
                        request = new ExecuteRequest(target);
                    }
                    break;
                case POST_TYPE_OPER_WRITE_REPLACE:
                    // Request to write a <b>String Single-Instance Resource</b> using the TLV content format.
                    if (resource != null && contentFormat != null) {
                        if (contentFormat.equals(ContentFormat.TLV) && !resource.multiple) {
                            request = this.getWriteRequestSingleResource(null, resultIds.getObjectId(), resultIds.getObjectInstanceId(), resultIds.getResourceId(), params, resource.type, registration);
                        }
                        // Mode.REPLACE && Request to write a <b>String Single-Instance Resource</b> using the given content format (TEXT, TLV, JSON)
                        else if (!contentFormat.equals(ContentFormat.TLV) && !resource.multiple) {
                            request = this.getWriteRequestSingleResource(contentFormat, resultIds.getObjectId(), resultIds.getObjectInstanceId(), resultIds.getResourceId(), params, resource.type, registration);
                        }
                    }
                    break;
                case PUT_TYPE_OPER_WRITE_UPDATE:
                    if (resultIds.getResourceId() >= 0) {
//                        ResourceModel resourceModel = leshanServer.getModelProvider().getObjectModel(registration).getObjectModel(resultIds.getObjectId()).resources.get(resultIds.getResourceId());
//                        ResourceModel.Type typeRes = resourceModel.type;
                        LwM2mNode node = LwM2mSingleResource.newStringResource(resultIds.getResourceId(), (String) this.converter.convertValue(params, resource.type, ResourceModel.Type.STRING, resultIds));
                        request = new WriteRequest(WriteRequest.Mode.UPDATE, contentFormat, target, node);
                    }
                    break;
                case PUT_TYPE_OPER_WRITE_ATTRIBUTES:
                    /**
                     * As example:
                     * a)Write-Attributes/3/0/9?pmin=1 means the Battery Level value will be notified
                     * to the Server with a minimum interval of 1sec;
                     * this value is set at theResource level.
                     * b)Write-Attributes/3/0/9?pmin means the Battery Level will be notified
                     * to the Server with a minimum value (pmin) given by the default one
                     * (resource 2 of Object Server ID=1),
                     * or with another value if this Attribute has been set at another level
                     * (Object or Object Instance: see section5.1.1).
                     * c)Write-Attributes/3/0?pmin=10 means that all Resources of Instance 0 of the Object ‘Device (ID:3)’
                     * will be notified to the Server with a minimum interval of 10 sec;
                     * this value is set at the Object Instance level.
                     * d)Write-Attributes /3/0/9?gt=45&st=10 means the Battery Level will be notified to the Server
                     * when:
                     * a.old value is 20 and new value is 35 due to step condition
                     * b.old value is 45 and new value is 50 due to gt condition
                     * c.old value is 50 and new value is 40 due to both gt and step conditions
                     * d.old value is 35 and new value is 20 due to step conditione)
                     * Write-Attributes /3/0/9?lt=20&gt=85&st=10 means the Battery Level will be notified to the Server
                     * when:
                     * a.old value is 17 and new value is 24 due to lt condition
                     * b.old value is 75 and new value is 90 due to both gt and step conditions
                     *   String uriQueries = "pmin=10&pmax=60";
                     *   AttributeSet attributes = AttributeSet.parse(uriQueries);
                     *   WriteAttributesRequest request = new WriteAttributesRequest(target, attributes);
                     *   Attribute gt = new Attribute(GREATER_THAN, Double.valueOf("45"));
                     *   Attribute st = new Attribute(LESSER_THAN, Double.valueOf("10"));
                     *   Attribute pmax = new Attribute(MAXIMUM_PERIOD, "60");
                     *   Attribute [] attrs = {gt, st};
                     */
                    Attribute pmin = new Attribute(MINIMUM_PERIOD, Integer.toUnsignedLong(Integer.parseInt("1")));
                    Attribute[] attrs = {pmin};
                    AttributeSet attrSet = new AttributeSet(attrs);
                    if (resultIds.isResource()) {
                        request = new WriteAttributesRequest(resultIds.getObjectId(), resultIds.getObjectInstanceId(), resultIds.getResourceId(), attrSet);
                    } else if (resultIds.isObjectInstance()) {
                        request = new WriteAttributesRequest(resultIds.getObjectId(), resultIds.getObjectInstanceId(), attrSet);
                    } else if (resultIds.getObjectId() >= 0) {
                        request = new WriteAttributesRequest(resultIds.getObjectId(), attrSet);
                    }
                    break;
                default:
            }

            if (request != null) {
                this.sendRequest(registration, request, timeoutInMs);
            }
        }
    }

    /**
     *
     * @param registration -
     * @param request -
     * @param timeoutInMs -
     */

    @SuppressWarnings("unchecked")
    private void sendRequest(Registration registration, DownlinkRequest request, long timeoutInMs) {
        LwM2mClient lwM2MClient = lwM2mClientContext.getLwM2mClientWithReg(registration, null);
        leshanServer.send(registration, request, timeoutInMs, (ResponseCallback<?>) response -> {
            if (!lwM2MClient.isInit()) {
                lwM2MClient.initValue(this.serviceImpl, request.getPath().toString());
            }
            if (isSuccess(((Response) response.getCoapResponse()).getCode())) {
                this.handleResponse(registration, request.getPath().toString(), response, request);
                if (request instanceof WriteRequest && ((WriteRequest) request).isReplaceRequest()) {
                    String msg = String.format("%s: sendRequest Replace: CoapCde - %s Lwm2m code - %d name - %s Resource path - %s value - %s SendRequest to Client",
                            LOG_LW2M_INFO, ((Response) response.getCoapResponse()).getCode(), response.getCode().getCode(), response.getCode().getName(), request.getPath().toString(),
                            ((LwM2mSingleResource) ((WriteRequest) request).getNode()).getValue().toString());
                    serviceImpl.sentLogsToThingsboard(msg, registration);
                    log.info("[{}] [{}] - [{}] [{}] Update SendRequest[{}]", registration.getEndpoint(), ((Response) response.getCoapResponse()).getCode(), response.getCode(), request.getPath().toString(),
                            ((LwM2mSingleResource) ((WriteRequest) request).getNode()).getValue());
                }
            } else {
                String msg = String.format("%s: sendRequest: CoapCode - %s Lwm2m code - %d name - %s Resource path - %s  SendRequest to Client", LOG_LW2M_ERROR,
                        ((Response) response.getCoapResponse()).getCode(), response.getCode().getCode(), response.getCode().getName(), request.getPath().toString());
                serviceImpl.sentLogsToThingsboard(msg, registration);
                log.error("[{}], [{}] - [{}] [{}] error SendRequest", registration.getEndpoint(), ((Response) response.getCoapResponse()).getCode(), response.getCode(), request.getPath().toString());
            }
        }, e -> {
            if (!lwM2MClient.isInit()) {
                lwM2MClient.initValue(this.serviceImpl, request.getPath().toString());
            }
            String msg = String.format("%s: sendRequest: Resource path - %s msg error - %s  SendRequest to Client",
                    LOG_LW2M_ERROR, request.getPath().toString(), e.toString());
            serviceImpl.sentLogsToThingsboard(msg, registration);
            log.error("[{}] - [{}] error SendRequest", request.getPath().toString(), e.toString());
        });

    }

    private WriteRequest getWriteRequestSingleResource(ContentFormat contentFormat, Integer objectId, Integer instanceId, Integer resourceId, Object value, ResourceModel.Type type, Registration registration) {
        try {
            switch (type) {
                case STRING:    // String
                    return (contentFormat == null) ? new WriteRequest(objectId, instanceId, resourceId, value.toString()) : new WriteRequest(contentFormat, objectId, instanceId, resourceId, value.toString());
                case INTEGER:   // Long
                    final long valueInt = Integer.toUnsignedLong(Integer.parseInt(value.toString()));
                    return (contentFormat == null) ? new WriteRequest(objectId, instanceId, resourceId, valueInt) : new WriteRequest(contentFormat, objectId, instanceId, resourceId, valueInt);
                case OBJLNK:    // ObjectLink
                    return (contentFormat == null) ? new WriteRequest(objectId, instanceId, resourceId, ObjectLink.fromPath(value.toString())) : new WriteRequest(contentFormat, objectId, instanceId, resourceId, ObjectLink.fromPath(value.toString()));
                case BOOLEAN:   // Boolean
                    return (contentFormat == null) ? new WriteRequest(objectId, instanceId, resourceId, Boolean.parseBoolean(value.toString())) : new WriteRequest(contentFormat, objectId, instanceId, resourceId, Boolean.parseBoolean(value.toString()));
                case FLOAT:     // Double
                    return (contentFormat == null) ? new WriteRequest(objectId, instanceId, resourceId, Double.parseDouble(value.toString())) : new WriteRequest(contentFormat, objectId, instanceId, resourceId, Double.parseDouble(value.toString()));
                case TIME:      // Date
                    Date date =  new Date(Long.decode(value.toString()));
                    return (contentFormat == null) ? new WriteRequest(objectId, instanceId, resourceId, date) : new WriteRequest(contentFormat, objectId, instanceId, resourceId, date);
                case OPAQUE:    // byte[] value, base64
                    return (contentFormat == null) ? new WriteRequest(objectId, instanceId, resourceId, Hex.decodeHex(value.toString().toCharArray())) : new WriteRequest(contentFormat, objectId, instanceId, resourceId, Hex.decodeHex(value.toString().toCharArray()));
                default:
            }
            return null;
        } catch (NumberFormatException e) {
            String patn = "/" + objectId + "/" + instanceId + "/" + resourceId;
            String msg = String.format(LOG_LW2M_ERROR + ": NumberFormatException: Resource path - %s type - %s value - %s msg error - %s  SendRequest to Client",
                    patn, type, value, e.toString());
            serviceImpl.sentLogsToThingsboard(msg, registration);
            log.error("Path: [{}] type: [{}] value: [{}] errorMsg: [{}]]", patn, type, value, e.toString());
            return null;
        }
    }

    private void handleResponse(Registration registration, final String path, LwM2mResponse response, DownlinkRequest request) {
        executorResponse.submit(() -> {
            try {
                sendResponse(registration, path, response, request);
            } catch (Exception e) {
                log.error("[{}] endpoint [{}] path [{}] Exception Unable to after send response.", registration.getEndpoint(), path, e);
            }
        });
    }

    /**
     * processing a response from a client
     * @param registration -
     * @param path -
     * @param response -
     */
    private void sendResponse(Registration registration, String path, LwM2mResponse response, DownlinkRequest request) {
        if (response instanceof ReadResponse) {
            serviceImpl.onObservationResponse(registration, path, (ReadResponse) response);
        } else if (response instanceof CancelObservationResponse) {
            log.info("[{}] Path [{}] CancelObservationResponse 3_Send", path, response);
        } else if (response instanceof DeleteResponse) {
            log.info("[{}] Path [{}] DeleteResponse 5_Send", path, response);
        } else if (response instanceof DiscoverResponse) {
            log.info("[{}] Path [{}] DiscoverResponse 6_Send", path, response);
        } else if (response instanceof ExecuteResponse) {
            log.info("[{}] Path [{}] ExecuteResponse  7_Send", path, response);
        } else if (response instanceof WriteAttributesResponse) {
            log.info("[{}] Path [{}] WriteAttributesResponse 8_Send", path, response);
        } else if (response instanceof WriteResponse) {
            log.info("[{}] Path [{}] WriteAttributesResponse 9_Send", path, response);
            serviceImpl.onWriteResponseOk(registration, path, (WriteRequest) request);
        }
    }
}
