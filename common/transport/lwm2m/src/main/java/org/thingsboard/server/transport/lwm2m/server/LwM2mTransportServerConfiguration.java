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
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeDecoder;
import org.eclipse.leshan.core.node.codec.DefaultLwM2mNodeEncoder;
import org.eclipse.leshan.core.util.Hex;
import org.eclipse.leshan.server.californium.LeshanServer;
import org.eclipse.leshan.server.californium.LeshanServerBuilder;
import org.eclipse.leshan.server.californium.registration.CaliforniumRegistrationStore;
import org.eclipse.leshan.server.model.LwM2mModelProvider;
import org.eclipse.leshan.server.model.VersionedModelProvider;
import org.eclipse.leshan.server.security.DefaultAuthorizer;
import org.eclipse.leshan.server.security.EditableSecurityStore;
import org.eclipse.leshan.server.security.SecurityChecker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.thingsboard.server.queue.util.TbLwM2mTransportComponent;
import org.thingsboard.server.transport.lwm2m.utils.LwM2mValueConverterImpl;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;

import static org.eclipse.californium.scandium.dtls.cipher.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256;
import static org.eclipse.californium.scandium.dtls.cipher.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8;
import static org.eclipse.californium.scandium.dtls.cipher.CipherSuite.TLS_PSK_WITH_AES_128_CBC_SHA256;
import static org.eclipse.californium.scandium.dtls.cipher.CipherSuite.TLS_PSK_WITH_AES_128_CCM_8;
import static org.thingsboard.server.transport.lwm2m.server.LwM2mTransportHandler.getCoapConfig;

@Slf4j
@Component("LwM2MTransportServerConfiguration")
@TbLwM2mTransportComponent
public class LwM2mTransportServerConfiguration {
    private PublicKey publicKey;
    private PrivateKey privateKey;
    private boolean pskMode = false;

    @Autowired
    private LwM2mTransportContextServer context;

    @Autowired
    private CaliforniumRegistrationStore registrationStore;

    @Autowired
    private EditableSecurityStore securityStore;

    @Bean
    public LeshanServer getLeshanServer() {
        log.info("Starting LwM2M transport Server... PostConstruct");
        return this.getLhServer(this.context.getLwM2MTransportConfigServer().getServerPortNoSec(), this.context.getLwM2MTransportConfigServer().getServerPortSecurity());
    }

    private LeshanServer getLhServer(Integer serverPortNoSec, Integer serverSecurePort) {
        LeshanServerBuilder builder = new LeshanServerBuilder();
        builder.setLocalAddress(this.context.getLwM2MTransportConfigServer().getServerHost(), serverPortNoSec);
        builder.setLocalSecureAddress(this.context.getLwM2MTransportConfigServer().getServerHostSecurity(), serverSecurePort);
        builder.setDecoder(new DefaultLwM2mNodeDecoder());
        /** Use a magic converter to support bad type send by the UI. */
        builder.setEncoder(new DefaultLwM2mNodeEncoder(LwM2mValueConverterImpl.getInstance()));

        /** Create CoAP Config */
        builder.setCoapConfig(getCoapConfig(serverPortNoSec, serverSecurePort));

        /** Define model provider (Create Models )*/
        LwM2mModelProvider modelProvider = new VersionedModelProvider(this.context.getLwM2MTransportConfigServer().getModelsValue());
        builder.setObjectModelProvider(modelProvider);

        /**  Create credentials */
        this.setServerWithCredentials(builder);

        /** Set securityStore with new registrationStore */
        builder.setSecurityStore(securityStore);
        builder.setRegistrationStore(registrationStore);


        /** Create DTLS Config */
        DtlsConnectorConfig.Builder dtlsConfig = new DtlsConnectorConfig.Builder();
        dtlsConfig.setRecommendedSupportedGroupsOnly(this.context.getLwM2MTransportConfigServer().isRecommendedSupportedGroups());
        dtlsConfig.setRecommendedCipherSuitesOnly(this.context.getLwM2MTransportConfigServer().isRecommendedCiphers());
        if (this.pskMode) {
            dtlsConfig.setSupportedCipherSuites(
                    TLS_PSK_WITH_AES_128_CCM_8,
                    TLS_PSK_WITH_AES_128_CBC_SHA256);
        } else {
            dtlsConfig.setSupportedCipherSuites(
                    TLS_PSK_WITH_AES_128_CCM_8,
                    TLS_PSK_WITH_AES_128_CBC_SHA256,
                    TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8,
                    TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256);
        }

        /** Set DTLS Config */
        builder.setDtlsConfig(dtlsConfig);

        /** Create LWM2M server */
        return builder.build();
    }

    private void setServerWithCredentials(LeshanServerBuilder builder) {
        try {
            if (this.context.getLwM2MTransportConfigServer().getKeyStoreValue() != null) {
                if (this.setBuilderX509(builder)) {
                    X509Certificate rootCAX509Cert = (X509Certificate) this.context.getLwM2MTransportConfigServer().getKeyStoreValue().getCertificate(this.context.getLwM2MTransportConfigServer().getRootAlias());
                    if (rootCAX509Cert != null) {
                        X509Certificate[] trustedCertificates = new X509Certificate[1];
                        trustedCertificates[0] = rootCAX509Cert;
                        builder.setTrustedCertificates(trustedCertificates);
                    } else {
                        /** by default trust all */
                        builder.setTrustedCertificates(new X509Certificate[0]);
                    }
                    /** Set securityStore with registrationStore*/
                    builder.setAuthorizer(new DefaultAuthorizer(securityStore, new SecurityChecker() {
                        @Override
                        protected boolean matchX509Identity(String endpoint, String receivedX509CommonName,
                                                            String expectedX509CommonName) {
                            return endpoint.startsWith(expectedX509CommonName);
                        }
                    }));
                }
            } else if (this.setServerRPK(builder)) {
                this.infoPramsUri("RPK");
                this.infoParamsServerKey(this.publicKey, this.privateKey);
            } else {
                /** by default trust all */
                builder.setTrustedCertificates(new X509Certificate[0]);
                log.info("Unable to load X509 files for LWM2MServer");
                this.pskMode = true;
                this.infoPramsUri("PSK");
            }
        } catch (KeyStoreException ex) {
            log.error("[{}] Unable to load X509 files server", ex.getMessage());
        }
    }

    private boolean setBuilderX509(LeshanServerBuilder builder) {
        /**
         * For deb => KeyStorePathFile == yml or commandline: KEY_STORE_PATH_FILE
         * For idea => KeyStorePathResource == common/transport/lwm2m/src/main/resources/credentials: in LwM2MTransportContextServer: credentials/serverKeyStore.jks
         */
        try {
            X509Certificate serverCertificate = (X509Certificate) this.context.getLwM2MTransportConfigServer().getKeyStoreValue().getCertificate(this.context.getLwM2MTransportConfigServer().getServerAlias());
            PrivateKey privateKey = (PrivateKey) this.context.getLwM2MTransportConfigServer().getKeyStoreValue().getKey(this.context.getLwM2MTransportConfigServer().getServerAlias(), this.context.getLwM2MTransportConfigServer().getKeyStorePasswordServer() == null ? null : this.context.getLwM2MTransportConfigServer().getKeyStorePasswordServer().toCharArray());
            PublicKey publicKey = serverCertificate.getPublicKey();
            if (serverCertificate != null &&
                    privateKey != null && privateKey.getEncoded().length > 0 &&
                    publicKey != null && publicKey.getEncoded().length > 0) {
                builder.setPublicKey(serverCertificate.getPublicKey());
                builder.setPrivateKey(privateKey);
                builder.setCertificateChain(new X509Certificate[]{serverCertificate});
                this.infoParamsServerX509(serverCertificate, publicKey, privateKey);
                return true;
            } else {
                return false;
            }
        } catch (Exception ex) {
            log.error("[{}] Unable to load KeyStore  files server", ex.getMessage());
            return false;
        }
    }

    private void infoParamsServerX509(X509Certificate certificate, PublicKey publicKey, PrivateKey privateKey) {
        try {
            infoPramsUri("X509");
            log.info("\n- X509 Certificate (Hex): [{}]",
                    Hex.encodeHexString(certificate.getEncoded()));
            this.infoParamsServerKey(publicKey, privateKey);
        } catch (CertificateEncodingException e) {
            log.error("", e);
        }
    }

    private void infoPramsUri(String mode) {
        log.info("Server uses [{}]: serverNoSecureURI : [{}], serverSecureURI : [{}]",
                mode,
                this.context.getLwM2MTransportConfigServer().getServerHost() + ":" + this.context.getLwM2MTransportConfigServer().getServerPortNoSec(),
                this.context.getLwM2MTransportConfigServer().getServerHostSecurity() + ":" + this.context.getLwM2MTransportConfigServer().getServerPortSecurity());
    }

    private boolean setServerRPK(LeshanServerBuilder builder) {
        try {
            this.generateKeyForRPK();
            if (this.publicKey != null && this.publicKey.getEncoded().length > 0 &&
                    this.privateKey != null && this.privateKey.getEncoded().length > 0) {
                builder.setPublicKey(this.publicKey);
                builder.setPrivateKey(this.privateKey);
                return true;
            }
        } catch (NoSuchAlgorithmException | InvalidParameterSpecException | InvalidKeySpecException e) {
            log.error("Fail create Server with RPK", e);
        }
        return false;
    }


    /**
     * From yml: server
     * public_x: "${LWM2M_SERVER_PUBLIC_X:405354ea8893471d9296afbc8b020a5c6201b0bb25812a53b849d4480fa5f069}"
     * public_y: "${LWM2M_SERVER_PUBLIC_Y:30c9237e946a3a1692c1cafaa01a238a077f632c99371348337512363f28212b}"
     * private_encoded: "${LWM2M_SERVER_PRIVATE_ENCODED:274671fe40ce937b8a6352cf0a418e8a39e4bf0bb9bf74c910db953c20c73802}"
     */
    private void generateKeyForRPK() throws NoSuchAlgorithmException, InvalidParameterSpecException, InvalidKeySpecException {
        /** Get Elliptic Curve Parameter spec for secp256r1 */
        AlgorithmParameters algoParameters = AlgorithmParameters.getInstance("EC");
        algoParameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec parameterSpec = algoParameters.getParameterSpec(ECParameterSpec.class);
        if (this.context.getLwM2MTransportConfigServer().getServerPublicX() != null &&
                !this.context.getLwM2MTransportConfigServer().getServerPublicX().isEmpty() &&
                this.context.getLwM2MTransportConfigServer().getServerPublicY() != null &&
                !this.context.getLwM2MTransportConfigServer().getServerPublicY().isEmpty()) {
            /** Get point values */
            byte[] publicX = Hex.decodeHex(this.context.getLwM2MTransportConfigServer().getServerPublicX().toCharArray());
            byte[] publicY = Hex.decodeHex(this.context.getLwM2MTransportConfigServer().getServerPublicY().toCharArray());
            /** Create key specs */
            KeySpec publicKeySpec = new ECPublicKeySpec(new ECPoint(new BigInteger(publicX), new BigInteger(publicY)),
                    parameterSpec);
            /** Get keys */
            this.publicKey = KeyFactory.getInstance("EC").generatePublic(publicKeySpec);
        }
        if (this.context.getLwM2MTransportConfigServer().getServerPrivateEncoded() != null &&
                !this.context.getLwM2MTransportConfigServer().getServerPrivateEncoded().isEmpty()) {
            /** Get private key */
            byte[] privateS = Hex.decodeHex(this.context.getLwM2MTransportConfigServer().getServerPrivateEncoded().toCharArray());
            try {
                this.privateKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(privateS));
            } catch (InvalidKeySpecException ignore2) {
                log.error("Invalid Server rpk.PrivateKey.getEncoded () [{}}]. PrivateKey has no EC algorithm", this.context.getLwM2MTransportConfigServer().getServerPrivateEncoded());
            }
        }
    }

    private void infoParamsServerKey(PublicKey publicKey, PrivateKey privateKey) {
        /** Get x coordinate */
        byte[] x = ((ECPublicKey) publicKey).getW().getAffineX().toByteArray();
        if (x[0] == 0)
            x = Arrays.copyOfRange(x, 1, x.length);

        /** Get Y coordinate */
        byte[] y = ((ECPublicKey) publicKey).getW().getAffineY().toByteArray();
        if (y[0] == 0)
            y = Arrays.copyOfRange(y, 1, y.length);

        /** Get Curves params */
        String params = ((ECPublicKey) publicKey).getParams().toString();
        String privHex = Hex.encodeHexString(privateKey.getEncoded());
        log.info(" \n- Public Key (Hex): [{}] \n" +
                        "- Private Key (Hex): [{}], \n" +
                        "public_x: \"${LWM2M_SERVER_PUBLIC_X:{}}\" \n" +
                        "public_y: \"${LWM2M_SERVER_PUBLIC_Y:{}}\" \n" +
                        "private_encoded: \"${LWM2M_SERVER_PRIVATE_ENCODED:{}}\" \n" +
                        "- Elliptic Curve parameters  : [{}]",
                Hex.encodeHexString(publicKey.getEncoded()),
                privHex,
                Hex.encodeHexString(x),
                Hex.encodeHexString(y),
                privHex,
                params);
    }

}
