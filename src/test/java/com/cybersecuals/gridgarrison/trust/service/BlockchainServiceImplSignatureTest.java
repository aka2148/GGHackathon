package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.shared.dto.FirmwareHash;
import com.cybersecuals.gridgarrison.trust.contract.FirmwareRegistryContract;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.protocol.core.RemoteCall;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlockchainServiceImplSignatureTest {

    @Test
    void verifiesSignatureAndHashAsVerified() throws Exception {
        KeyPair keyPair = newRsaKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String goldenHash = "0xabc123";
        String signature = sign(goldenHash, keyPair);

        BlockchainServiceImpl service = new BlockchainServiceImpl();
        FirmwareRegistryContract contract = mock(FirmwareRegistryContract.class);
        RemoteCall<FirmwareRegistryContract.SignedGoldenRecord> call = mock(RemoteCall.class);

        when(contract.getSignedGoldenRecord("CS-101")).thenReturn(call);
        when(call.send()).thenReturn(new FirmwareRegistryContract.SignedGoldenRecord(
            goldenHash,
            signature,
            "ACME-MFG",
            BigInteger.ONE
        ));

        ReflectionTestUtils.setField(service, "firmwareRegistry", contract);
        ReflectionTestUtils.setField(service, "contractAddress", "0xcontract");
        ReflectionTestUtils.setField(service, "trustedManufacturerId", "ACME-MFG");
        ReflectionTestUtils.setField(service, "manufacturerPublicKeyBase64", publicKey);

        FirmwareHash input = FirmwareHash.builder()
            .stationId("CS-101")
            .reportedHash(goldenHash)
            .firmwareVersion("1.0.0")
            .reportedAt(Instant.now())
            .status(FirmwareHash.VerificationStatus.PENDING)
            .build();

        TrustVerificationResult result = service.verifyGoldenHashWithEvidence(input).join();

        assertThat(result.firmwareHash().getStatus()).isEqualTo(FirmwareHash.VerificationStatus.VERIFIED);
        assertThat(result.firmwareHash().getSignatureVerified()).isTrue();
        assertThat(result.evidence().verdict()).isEqualTo(TrustEvidence.Verdict.VERIFIED);
    }

    @Test
    void failsVerificationWhenSignatureIsInvalid() throws Exception {
        KeyPair keyPair = newRsaKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String goldenHash = "0xabc123";
        String invalidSignature = sign("0xnot-the-golden-hash", keyPair);

        BlockchainServiceImpl service = new BlockchainServiceImpl();
        FirmwareRegistryContract contract = mock(FirmwareRegistryContract.class);
        RemoteCall<FirmwareRegistryContract.SignedGoldenRecord> call = mock(RemoteCall.class);

        when(contract.getSignedGoldenRecord("CS-101")).thenReturn(call);
        when(call.send()).thenReturn(new FirmwareRegistryContract.SignedGoldenRecord(
            goldenHash,
            invalidSignature,
            "ACME-MFG",
            BigInteger.ONE
        ));

        ReflectionTestUtils.setField(service, "firmwareRegistry", contract);
        ReflectionTestUtils.setField(service, "contractAddress", "0xcontract");
        ReflectionTestUtils.setField(service, "trustedManufacturerId", "ACME-MFG");
        ReflectionTestUtils.setField(service, "manufacturerPublicKeyBase64", publicKey);

        FirmwareHash input = FirmwareHash.builder()
            .stationId("CS-101")
            .reportedHash(goldenHash)
            .firmwareVersion("1.0.0")
            .reportedAt(Instant.now())
            .status(FirmwareHash.VerificationStatus.PENDING)
            .build();

        TrustVerificationResult result = service.verifyGoldenHashWithEvidence(input).join();

        assertThat(result.firmwareHash().getStatus()).isEqualTo(FirmwareHash.VerificationStatus.TAMPERED);
        assertThat(result.firmwareHash().getSignatureVerified()).isFalse();
        assertThat(result.evidence().rationale()).contains("signature verification failed");
    }

    private static KeyPair newRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String sign(String value, KeyPair keyPair) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(value.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}
