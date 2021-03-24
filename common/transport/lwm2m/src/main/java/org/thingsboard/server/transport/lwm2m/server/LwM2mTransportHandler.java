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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.leshan.core.model.ResourceModel;
import org.eclipse.leshan.core.node.LwM2mMultipleResource;
import org.eclipse.leshan.core.node.LwM2mNode;
import org.eclipse.leshan.core.node.LwM2mObject;
import org.eclipse.leshan.core.node.LwM2mObjectInstance;
import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.core.node.LwM2mSingleResource;
import org.eclipse.leshan.core.node.codec.CodecException;
import org.eclipse.leshan.core.util.Hex;
import org.eclipse.leshan.server.californium.LeshanServerBuilder;
import org.nustaq.serialization.FSTConfiguration;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.device.profile.Lwm2mDeviceProfileTransportConfiguration;
import org.thingsboard.server.common.transport.TransportServiceCallback;
import org.thingsboard.server.transport.lwm2m.server.client.LwM2mClient;
import org.thingsboard.server.transport.lwm2m.server.client.LwM2mClientProfile;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.Optional;

@Slf4j
//@Component("LwM2MTransportHandler")
//@ConditionalOnExpression("('${service.type:null}'=='tb-transport' && '${transport.lwm2m.enabled:false}'=='true' )|| ('${service.type:null}'=='monolith' && '${transport.lwm2m.enabled}'=='true')")
public class LwM2mTransportHandler {

    // We choose a default timeout a bit higher to the MAX_TRANSMIT_WAIT(62-93s) which is the time from starting to
    // send a Confirmable message to the time when an acknowledgement is no longer expected.

    public static final String BASE_DEVICE_API_TOPIC = "v1/devices/me";
    public static final String ATTRIBUTE = "attribute";
    public static final String TELEMETRY = "telemetry";
    private static final String REQUEST = "/request";
    private static final String RESPONSE = "/response";
    private static final String ATTRIBUTES = "/" + ATTRIBUTE;
    public static final String TELEMETRIES = "/" + TELEMETRY;
    public static final String ATTRIBUTES_RESPONSE = ATTRIBUTES + RESPONSE;
    public static final String ATTRIBUTES_REQUEST = ATTRIBUTES + REQUEST;
    public static final String DEVICE_ATTRIBUTES_RESPONSE = ATTRIBUTES_RESPONSE + "/";
    public static final String DEVICE_ATTRIBUTES_REQUEST = ATTRIBUTES_REQUEST + "/";
    public static final String DEVICE_ATTRIBUTES_TOPIC = BASE_DEVICE_API_TOPIC + ATTRIBUTES;
    public static final String DEVICE_TELEMETRY_TOPIC = BASE_DEVICE_API_TOPIC + TELEMETRIES;

    public static final long DEFAULT_TIMEOUT = 2 * 60 * 1000L; // 2min in ms
    public static final String OBSERVE_ATTRIBUTE_TELEMETRY = "observeAttr";
    public static final String CLIENT_LWM2M_SETTINGS = "clientLwM2mSettings";
    public static final String KEY_NAME = "keyName";
    public static final String OBSERVE = "observe";
    public static final String BOOTSTRAP = "bootstrap";
    public static final String SERVERS = "servers";
    public static final String LWM2M_SERVER = "lwm2mServer";
    public static final String BOOTSTRAP_SERVER = "bootstrapServer";

    public static final String LOG_LW2M_TELEMETRY = "logLwm2m";
    public static final String LOG_LW2M_INFO = "info";
    public static final String LOG_LW2M_ERROR = "error";
    public static final String LOG_LW2M_WARN = "warn";


    public static final String CLIENT_NOT_AUTHORIZED = "Client not authorized";

    public static final String GET_TYPE_OPER_READ = "read";
    public static final String GET_TYPE_OPER_DISCOVER = "discover";
    public static final String GET_TYPE_OPER_OBSERVE = "observe";
    public static final String POST_TYPE_OPER_OBSERVE_CANCEL = "observeCancel";
    public static final String POST_TYPE_OPER_EXECUTE = "execute";
    /**
     * Replaces the Object Instance or the Resource(s) with the new value provided in the “Write” operation. (see
     * section 5.3.3 of the LW M2M spec).
     * if all resources are to be replaced
     */
    public static final String POST_TYPE_OPER_WRITE_REPLACE = "replace";
    /**
     * Adds or updates Resources provided in the new value and leaves other existing Resources unchanged. (see section
     * 5.3.3 of the LW M2M spec).
     * if this is a partial update request
     */
    public static final String PUT_TYPE_OPER_WRITE_UPDATE = "update";
    public static final String PUT_TYPE_OPER_WRITE_ATTRIBUTES = "wright-attributes";

    public static final String EVENT_AWAKE = "AWAKE";
    public static final String SERVICE_CHANNEL = "SERVICE";
    public static final String RESPONSE_CHANNEL = "RESP";

//    @Autowired
//    @Qualifier("LeshanServerCert")
//    private LeshanServer lhServerCert;
//
//    @Autowired
//    @Qualifier("LeshanServerNoSecPskRpk")
//    private LeshanServer lhServerNoSecPskRpk;

//    @Autowired
//    @Qualifier("ServerListenerCert")
//    private LwM2mServerListener serverListenerCert;
//
//    @Autowired
//    @Qualifier("ServerListenerNoSecPskRpk")
//    private LwM2mServerListener serverListenerNoSecPskRpk;


//    @PostConstruct
//    public void init() {
//        try {
//            serverListenerCert.init(lhServerCert);
//            this.lhServerCert.getRegistrationService().addListener(serverListenerCert.registrationListener);
//            this.lhServerCert.getPresenceService().addListener(serverListenerCert.presenceListener);
//            this.lhServerCert.getObservationService().addListener(serverListenerCert.observationListener);
//            serverListenerNoSecPskRpk.init(lhServerNoSecPskRpk);
//            this.lhServerNoSecPskRpk.getRegistrationService().addListener(serverListenerNoSecPskRpk.registrationListener);
//            this.lhServerNoSecPskRpk.getPresenceService().addListener(serverListenerNoSecPskRpk.presenceListener);
//            this.lhServerNoSecPskRpk.getObservationService().addListener(serverListenerNoSecPskRpk.observationListener);
//        } catch (Exception e) {
//            log.error("init [{}]", e.toString());
//        }
//    }

    public static NetworkConfig getCoapConfig(Integer serverPortNoSec, Integer serverSecurePort) {
        NetworkConfig coapConfig;
        File configFile = new File(NetworkConfig.DEFAULT_FILE_NAME);
        if (configFile.isFile()) {
            coapConfig = new NetworkConfig();
            coapConfig.load(configFile);
        } else {
            coapConfig = LeshanServerBuilder.createDefaultNetworkConfig();
            coapConfig.store(configFile);
        }
        coapConfig.setString("COAP_PORT", Integer.toString(serverPortNoSec));
        coapConfig.setString("COAP_SECURE_PORT", Integer.toString(serverSecurePort));
        return coapConfig;
    }

    public static boolean equalsResourceValue(Object valueOld, Object valueNew, ResourceModel.Type type, LwM2mPath resourcePath) throws CodecException {
        switch (type) {
            case BOOLEAN:
            case INTEGER:
            case FLOAT:
                return String.valueOf(valueOld).equals(String.valueOf(valueNew));
            case TIME:
                return ((Date) valueOld).getTime() == ((Date) valueNew).getTime();
            case STRING:
            case OBJLNK:
                return valueOld.equals(valueNew);
            case OPAQUE:
                return Hex.decodeHex(((String) valueOld).toCharArray()).equals(Hex.decodeHex(((String) valueNew).toCharArray()));
            default:
                throw new CodecException("Invalid value type for resource %s, type %s", resourcePath, type);
        }
    }

    public static LwM2mNode getLvM2mNodeToObject(LwM2mNode content) {
        if (content instanceof LwM2mObject) {
            return (LwM2mObject) content;
        } else if (content instanceof LwM2mObjectInstance) {
            return (LwM2mObjectInstance) content;
        } else if (content instanceof LwM2mSingleResource) {
            return (LwM2mSingleResource) content;
        } else if (content instanceof LwM2mMultipleResource) {
            return (LwM2mMultipleResource) content;
        }
        return null;
    }

    public static LwM2mClientProfile getNewProfileParameters(JsonObject profilesConfigData) {
        LwM2mClientProfile lwM2MClientProfile = new LwM2mClientProfile();
        lwM2MClientProfile.setPostClientLwM2mSettings(profilesConfigData.get(CLIENT_LWM2M_SETTINGS).getAsJsonObject());
        lwM2MClientProfile.setPostKeyNameProfile(profilesConfigData.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(KEY_NAME).getAsJsonObject());
        lwM2MClientProfile.setPostAttributeProfile(profilesConfigData.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(ATTRIBUTE).getAsJsonArray());
        lwM2MClientProfile.setPostTelemetryProfile(profilesConfigData.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(TELEMETRY).getAsJsonArray());
        lwM2MClientProfile.setPostObserveProfile(profilesConfigData.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(OBSERVE).getAsJsonArray());
        return lwM2MClientProfile;
    }

    /**
     * @return deviceProfileBody with Observe&Attribute&Telemetry From Thingsboard
     * Example: 
     * property: {"clientLwM2mSettings": {
     *      clientUpdateValueAfterConnect: false;
     *       }
     * property: "observeAttr"
     * {"keyName": {
     * "/3/0/1": "modelNumber",
     * "/3/0/0": "manufacturer",
     * "/3/0/2": "serialNumber"
     * },
     * "attribute":["/2/0/1","/3/0/9"],
     * "telemetry":["/1/0/1","/2/0/1","/6/0/1"],
     * "observe":["/2/0","/2/0/0","/4/0/2"]}
     */
    public static LwM2mClientProfile getLwM2MClientProfileFromThingsboard(DeviceProfile deviceProfile) {
        if (deviceProfile != null && ((Lwm2mDeviceProfileTransportConfiguration) deviceProfile.getProfileData().getTransportConfiguration()).getProperties().size() > 0) {
            Object profile = ((Lwm2mDeviceProfileTransportConfiguration) deviceProfile.getProfileData().getTransportConfiguration()).getProperties();
            try {
                ObjectMapper mapper = new ObjectMapper();
                String profileStr = mapper.writeValueAsString(profile);
                JsonObject profileJson = (profileStr  != null) ? validateJson(profileStr) : null;
                return (getValidateCredentialsBodyFromThingsboard(profileJson)) ? LwM2mTransportHandler.getNewProfileParameters(profileJson) : null;
            } catch (IOException e) {
                log.error("", e);
            }
        }
        return null;
    }

    public static JsonObject getBootstrapParametersFromThingsboard(DeviceProfile deviceProfile) {
        if (deviceProfile != null && ((Lwm2mDeviceProfileTransportConfiguration) deviceProfile.getProfileData().getTransportConfiguration()).getProperties().size() > 0) {
            Object bootstrap = ((Lwm2mDeviceProfileTransportConfiguration) deviceProfile.getProfileData().getTransportConfiguration()).getProperties();
            try {
                ObjectMapper mapper = new ObjectMapper();
                String bootstrapStr = mapper.writeValueAsString(bootstrap);
                JsonObject objectMsg = (bootstrapStr != null) ? validateJson(bootstrapStr) : null;
                return (getValidateBootstrapProfileFromThingsboard(objectMsg)) ? objectMsg.get(BOOTSTRAP).getAsJsonObject() : null;
            } catch (IOException e) {
                log.error("", e);
            }
        }
        return null;
    }

    public static boolean getClientOnlyObserveAfterConnect (LwM2mClientProfile profile) {
        return profile.getPostClientLwM2mSettings().getAsJsonObject().has("clientOnlyObserveAfterConnect") &&
                profile.getPostClientLwM2mSettings().getAsJsonObject().get("clientOnlyObserveAfterConnect").getAsBoolean();
    }

    private static boolean getValidateCredentialsBodyFromThingsboard(JsonObject objectMsg) {
        return (objectMsg != null &&
                !objectMsg.isJsonNull() &&
                objectMsg.has(CLIENT_LWM2M_SETTINGS) &&
                !objectMsg.get(CLIENT_LWM2M_SETTINGS).isJsonNull() &&
                objectMsg.get(CLIENT_LWM2M_SETTINGS).isJsonObject() &&
                objectMsg.has(OBSERVE_ATTRIBUTE_TELEMETRY) &&
                !objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).isJsonNull() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).isJsonObject() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().has(KEY_NAME) &&
                !objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(KEY_NAME).isJsonNull() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(KEY_NAME).isJsonObject() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().has(ATTRIBUTE) &&
                !objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(ATTRIBUTE).isJsonNull() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(ATTRIBUTE).isJsonArray() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().has(TELEMETRY) &&
                !objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(TELEMETRY).isJsonNull() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(TELEMETRY).isJsonArray() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().has(OBSERVE) &&
                !objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(OBSERVE).isJsonNull() &&
                objectMsg.get(OBSERVE_ATTRIBUTE_TELEMETRY).getAsJsonObject().get(OBSERVE).isJsonArray());
    }

    private static boolean getValidateBootstrapProfileFromThingsboard(JsonObject objectMsg) {
        return (objectMsg != null &&
                !objectMsg.isJsonNull() &&
                objectMsg.has(BOOTSTRAP) &&
                objectMsg.get(BOOTSTRAP).isJsonObject() &&
                !objectMsg.get(BOOTSTRAP).isJsonNull() &&
                objectMsg.get(BOOTSTRAP).getAsJsonObject().has(SERVERS) &&
                !objectMsg.get(BOOTSTRAP).getAsJsonObject().get(SERVERS).isJsonNull() &&
                objectMsg.get(BOOTSTRAP).getAsJsonObject().get(SERVERS).isJsonObject() &&
                objectMsg.get(BOOTSTRAP).getAsJsonObject().has(BOOTSTRAP_SERVER) &&
                !objectMsg.get(BOOTSTRAP).getAsJsonObject().get(BOOTSTRAP_SERVER).isJsonNull() &&
                objectMsg.get(BOOTSTRAP).getAsJsonObject().get(BOOTSTRAP_SERVER).isJsonObject() &&
                objectMsg.get(BOOTSTRAP).getAsJsonObject().has(LWM2M_SERVER) &&
                !objectMsg.get(BOOTSTRAP).getAsJsonObject().get(LWM2M_SERVER).isJsonNull() &&
                objectMsg.get(BOOTSTRAP).getAsJsonObject().get(LWM2M_SERVER).isJsonObject());
    }


    public static JsonObject validateJson(String jsonStr) {
        JsonObject object = null;
        if (jsonStr != null && !jsonStr.isEmpty()) {
            String jsonValidFlesh = jsonStr.replaceAll("\\\\", "");
            jsonValidFlesh = jsonValidFlesh.replaceAll("\n", "");
            jsonValidFlesh = jsonValidFlesh.replaceAll("\t", "");
            jsonValidFlesh = jsonValidFlesh.replaceAll(" ", "");
            String jsonValid = (jsonValidFlesh.charAt(0) == '"' && jsonValidFlesh.charAt(jsonValidFlesh.length() - 1) == '"') ? jsonValidFlesh.substring(1, jsonValidFlesh.length() - 1) : jsonValidFlesh;
            try {
                object = new JsonParser().parse(jsonValid).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                log.error("[{}] Fail validateJson [{}]", jsonStr, e.getMessage());
            }
        }
        return object;
    }

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> decode(byte[] byteArray) {
        try {
            FSTConfiguration config = FSTConfiguration.createDefaultConfiguration();
            T msg = (T) config.asObject(byteArray);
            return Optional.ofNullable(msg);
        } catch (IllegalArgumentException e) {
            log.error("Error during deserialization message, [{}]", e.getMessage());
            return Optional.empty();
        }
    }

    public static String splitCamelCaseString(String s) {
        LinkedList<String> linkedListOut = new LinkedList<>();
        LinkedList<String> linkedList = new LinkedList<String>((Arrays.asList(s.split(" "))));
        linkedList.forEach(str -> {
            String strOut = str.replaceAll("\\W", "").replaceAll("_", "").toUpperCase();
            if (strOut.length() > 1) linkedListOut.add(strOut.charAt(0) + strOut.substring(1).toLowerCase());
            else linkedListOut.add(strOut);
        });
        linkedListOut.set(0, (linkedListOut.get(0).substring(0, 1).toLowerCase() + linkedListOut.get(0).substring(1)));
        return StringUtils.join(linkedListOut, "");
    }

    public static <T> TransportServiceCallback<Void> getAckCallback(LwM2mClient lwM2MClient, int requestId, String typeTopic) {
        return new TransportServiceCallback<Void>() {
            @Override
            public void onSuccess(Void dummy) {
                log.trace("[{}] [{}] - requestId [{}] - EndPoint  , Access AckCallback", typeTopic, requestId, lwM2MClient.getEndpoint());
            }

            @Override
            public void onError(Throwable e) {
                log.trace("[{}] Failed to publish msg", e.toString());
            }
        };
    }
}
