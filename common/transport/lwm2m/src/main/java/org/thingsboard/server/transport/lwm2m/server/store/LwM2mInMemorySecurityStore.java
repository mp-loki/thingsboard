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
package org.thingsboard.server.transport.lwm2m.server.store;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.leshan.core.util.Hex;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.server.security.InMemorySecurityStore;
import org.eclipse.leshan.server.security.SecurityInfo;
import org.eclipse.leshan.server.security.SecurityStoreListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.transport.lwm2m.secure.LwM2MSecurityMode;
import org.thingsboard.server.transport.lwm2m.secure.LwM2mCredentialsSecurityInfoValidator;
import org.thingsboard.server.transport.lwm2m.secure.ReadResultSecurityStore;
import org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandler;
import org.thingsboard.server.transport.lwm2m.server.client.LwM2mClient;
import org.thingsboard.server.transport.lwm2m.server.client.LwM2mClientProfile;
import org.thingsboard.server.transport.lwm2m.utils.TypeServer;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import static org.thingsboard.server.transport.lwm2m.secure.LwM2MSecurityMode.NO_SEC;

@Slf4j
//@Service("LwM2mInMemorySecurityStore")
//@TbLwM2mTransportComponent
@Deprecated
public class LwM2mInMemorySecurityStore extends InMemorySecurityStore {
    private static final boolean INFOS_ARE_COMPROMISED = false;

    // lock for the two maps
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Lock readLock = readWriteLock.readLock();
    private final Lock writeLock = readWriteLock.writeLock();
    private final Map<String /** registrationId */, LwM2mClient> sessions = new ConcurrentHashMap<>();
    private Map<UUID /** profileUUid */, LwM2mClientProfile> profiles = new ConcurrentHashMap<>();
    private SecurityStoreListener listener;

    @Autowired
    LwM2mCredentialsSecurityInfoValidator lwM2MCredentialsSecurityInfoValidator;

    /**
     * Start after DefaultAuthorizer or LwM2mPskStore
     * @param endPoint -
     * @return SecurityInfo
     */
    @Override
    public SecurityInfo getByEndpoint(String endPoint) {
        readLock.lock();
        try {
            String registrationId = this.getRegistrationId(endPoint, null);
            return (registrationId != null && sessions.size() > 0 && sessions.get(registrationId) != null) ?
                    sessions.get(registrationId).getSecurityInfo() : this.addLwM2MClientToSession(endPoint);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Start after LwM2mPskStore
     * @param identity -
     * @return SecurityInfo
     */
    @Override
    public SecurityInfo getByIdentity(String identity) {
        readLock.lock();
        try {
            String integrationId = this.getRegistrationId(null, identity);
            return (integrationId != null) ? sessions.get(integrationId).getSecurityInfo() : this.addLwM2MClientToSession(identity);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public Collection<SecurityInfo> getAll() {
        readLock.lock();
        try {
            return this.sessions.values().stream().map(LwM2mClient::getSecurityInfo).collect(Collectors.toUnmodifiableList());
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Removed registration Client from sessions and listener
     * @param registrationId if Client
     */
    public void delRemoveSessionAndListener(String registrationId) {
        writeLock.lock();
        try {
            LwM2mClient lwM2MClient = (sessions.get(registrationId) != null) ? sessions.get(registrationId) : null;
            if (lwM2MClient != null) {
                if (listener != null) {
                    listener.securityInfoRemoved(INFOS_ARE_COMPROMISED, lwM2MClient.getSecurityInfo());
                }
                sessions.remove(registrationId);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void setListener(SecurityStoreListener listener) {
        this.listener = listener;
    }

    public LwM2mClient getLwM2MClient(String endPoint, String identity) {
        Map.Entry<String, LwM2mClient> modelClients = endPoint != null ?
                this.sessions.entrySet().stream().filter(model -> endPoint.equals(model.getValue().getEndpoint())).findAny().orElse(null) :
                this.sessions.entrySet().stream().filter(model -> identity.equals(model.getValue().getIdentity())).findAny().orElse(null);
        return modelClients != null ? modelClients.getValue() : null;
    }

    public LwM2mClient getLwM2MClientWithReg(Registration registration, String registrationId) {
        return registrationId != null ?
                this.sessions.get(registrationId) :
                this.sessions.containsKey(registration.getId()) ?
                        this.sessions.get(registration.getId()) :
                        this.sessions.get(registration.getEndpoint());
    }

    public LwM2mClient getLwM2MClient(TransportProtos.SessionInfoProto sessionInfo) {
        return this.getSession(new UUID(sessionInfo.getSessionIdMSB(), sessionInfo.getSessionIdLSB())).entrySet().iterator().next().getValue();
    }

    /**
     * Update in sessions (LwM2MClient for key registration_Id) after starting registration LwM2MClient in LwM2MTransportServiceImpl
     * Remove from sessions LwM2MClient with key registration_Endpoint
     * @param registration -
     * @return LwM2MClient after adding it to session
     */
    public LwM2mClient updateInSessionsLwM2MClient(Registration registration) {
        writeLock.lock();
        try {
            if (this.sessions.get(registration.getEndpoint()) == null) {
                this.addLwM2MClientToSession(registration.getEndpoint());
            }
            LwM2mClient lwM2MClient = this.sessions.get(registration.getEndpoint());
            lwM2MClient.setRegistration(registration);
//            lwM2MClient.getAttributes().putAll(registration.getAdditionalRegistrationAttributes());
            this.sessions.remove(registration.getEndpoint());
            this.sessions.put(registration.getId(), lwM2MClient);
            return lwM2MClient;
        } finally {
            writeLock.unlock();
        }
    }

    private String getRegistrationId(String endPoint, String identity) {
        List<String> registrationIds = (endPoint != null) ?
                this.sessions.entrySet().stream().filter(model -> endPoint.equals(model.getValue().getEndpoint())).map(Map.Entry::getKey).collect(Collectors.toList()) :
                this.sessions.entrySet().stream().filter(model -> identity.equals(model.getValue().getIdentity())).map(Map.Entry::getKey).collect(Collectors.toList());
        return (registrationIds != null && registrationIds.size() > 0) ? registrationIds.get(0) : null;
    }

    public Registration getByRegistration(String registrationId) {
        return this.sessions.get(registrationId).getRegistration();
    }

    /**
     * Add new LwM2MClient to session
     * @param identity-
     * @return SecurityInfo. If error - SecurityInfoError
     * and log:
     * - FORBIDDEN - if there is no authorization
     * - profileUuid - if the device does not have a profile
     * - device - if the thingsboard does not have a device with a name equal to the identity
     */
    private SecurityInfo addLwM2MClientToSession(String identity) {
        ReadResultSecurityStore store = lwM2MCredentialsSecurityInfoValidator.createAndValidateCredentialsSecurityInfo(identity, TypeServer.CLIENT);
        if (store.getSecurityMode() < LwM2MSecurityMode.DEFAULT_MODE.code) {
            UUID profileUuid = (store.getDeviceProfile() != null && addUpdateProfileParameters(store.getDeviceProfile())) ? store.getDeviceProfile().getUuidId() : null;
            if (store.getSecurityInfo() != null && profileUuid != null) {
                String endpoint = store.getSecurityInfo().getEndpoint();
                sessions.put(endpoint, new LwM2mClient(endpoint, store.getSecurityInfo().getIdentity(), store.getSecurityInfo(), store.getMsg(), profileUuid, UUID.randomUUID()));
            } else if (store.getSecurityMode() == NO_SEC.code && profileUuid != null) {
                sessions.put(identity, new LwM2mClient(identity, null, null, store.getMsg(), profileUuid, UUID.randomUUID()));
            } else {
                log.error("Registration failed: FORBIDDEN/profileUuid/device [{}] , endpointId: [{}]", profileUuid, identity);
                /**
                 * Return Error securityInfo
                 */
                byte[] preSharedKey = Hex.decodeHex("0A0B".toCharArray());
                SecurityInfo infoError = SecurityInfo.newPreSharedKeyInfo("error", "error_identity", preSharedKey);
                return infoError;
            }
        }
        return store.getSecurityInfo();
    }

    public Map<String, LwM2mClient> getSession(UUID sessionUuId) {
        return this.sessions.entrySet().stream()
                .filter(e -> e.getValue().getSessionId().equals(sessionUuId))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Map<String, LwM2mClient> getSessions() {
        return this.sessions;
    }

    public Map<UUID, LwM2mClientProfile> getProfiles() {
        return this.profiles;
    }

    public LwM2mClientProfile getProfile(UUID profileUuId) {
        return this.profiles.get(profileUuId);
    }

    public LwM2mClientProfile getProfile(String registrationId) {
        UUID profileUUid = this.getSessions().get(registrationId).getProfileId();
        return this.getProfiles().get(profileUUid);
    }

    public Map<UUID, LwM2mClientProfile> setProfiles(Map<UUID, LwM2mClientProfile> profiles) {
        return this.profiles = profiles;
    }

    public boolean addUpdateProfileParameters(DeviceProfile deviceProfile) {
        LwM2mClientProfile lwM2MClientProfile = LwM2mTransportHandler.getLwM2MClientProfileFromThingsboard(deviceProfile);
        if (lwM2MClientProfile != null) {
            profiles.put(deviceProfile.getUuidId(), lwM2MClientProfile);
            return true;
        }
        return false;
    }
}
