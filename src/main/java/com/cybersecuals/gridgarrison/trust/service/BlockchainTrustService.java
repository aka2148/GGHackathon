package com.cybersecuals.gridgarrison.trust.service;

import com.cybersecuals.gridgarrison.trust.contract.FirmwareRegistryContract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.gas.DefaultGasProvider;

@Service
public class BlockchainTrustService {

    private final String rpcUrl;
    private final String privateKey;
    private final String contractAddress;

    public BlockchainTrustService(
        @Value("${gridgarrison.blockchain.rpc-url}") String rpcUrl,
        @Value("${gridgarrison.blockchain.private-key}") String privateKey,
        @Value("${gridgarrison.blockchain.contract-address}") String contractAddress
    ) {
        this.rpcUrl = rpcUrl;
        this.privateKey = privateKey;
        this.contractAddress = contractAddress;
    }

    public boolean isFirmwareValid(String chargerId, String liveHash) throws Exception {
        Web3j web3j = Web3j.build(new HttpService(rpcUrl));
        try {
            FirmwareRegistryContract contract = FirmwareRegistryContract.load(
                contractAddress,
                web3j,
                Credentials.create(privateKey),
                new DefaultGasProvider()
            );

            String goldenHash = contract.getGoldenHash(chargerId).send();
            return normalise(liveHash).equalsIgnoreCase(normalise(goldenHash));
        } finally {
            web3j.shutdown();
        }
    }

    private String normalise(String hash) {
        if (hash == null) {
            return "";
        }
        return hash.startsWith("0x") ? hash.substring(2) : hash;
    }
}
