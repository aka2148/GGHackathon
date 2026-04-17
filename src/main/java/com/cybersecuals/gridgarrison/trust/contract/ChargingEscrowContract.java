package com.cybersecuals.gridgarrison.trust.contract;

import org.web3j.abi.TypeReference;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.Contract;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

/**
 * Web3j wrapper for the ChargingEscrow contract.
 *
 * The deploy binary is loaded from src/main/resources/solidity/ChargingEscrow.bin,
 * which is generated from the truffle artifact at build/contracts/ChargingEscrow.json.
 */
public class ChargingEscrowContract extends Contract {

    public static final String BINARY = loadBinaryFromResources();

    protected ChargingEscrowContract(String contractAddress,
                                     Web3j web3j,
                                     Credentials credentials,
                                     ContractGasProvider gasProvider) {
        super(BINARY, contractAddress, web3j, credentials, gasProvider);
    }

    protected ChargingEscrowContract(String contractAddress,
                                     Web3j web3j,
                                     TransactionManager transactionManager,
                                     ContractGasProvider gasProvider) {
        super(BINARY, contractAddress, web3j, transactionManager, gasProvider);
    }

    public static RemoteCall<ChargingEscrowContract> deploy(
        Web3j web3j,
        Credentials credentials,
        ContractGasProvider gasProvider,
        String operator,
        String charger,
        String stationId,
        byte[] goldenHash,
        BigInteger targetSoc,
        BigInteger timeoutSeconds
    ) {
        String encodedConstructor = FunctionEncoder.encodeConstructor(Arrays.asList(
            new Address(operator),
            new Address(charger),
            new Utf8String(stationId),
            new Bytes32(normalizeBytes32(goldenHash)),
            new Uint8(targetSoc),
            new Uint256(timeoutSeconds)
        ));

        return deployRemoteCall(
            ChargingEscrowContract.class,
            web3j,
            credentials,
            gasProvider,
            BINARY,
            encodedConstructor
        );
    }

    public static RemoteCall<ChargingEscrowContract> deploy(
        Web3j web3j,
        TransactionManager transactionManager,
        ContractGasProvider gasProvider,
        String operator,
        String charger,
        String stationId,
        byte[] goldenHash,
        BigInteger targetSoc,
        BigInteger timeoutSeconds
    ) {
        String encodedConstructor = FunctionEncoder.encodeConstructor(Arrays.asList(
            new Address(operator),
            new Address(charger),
            new Utf8String(stationId),
            new Bytes32(normalizeBytes32(goldenHash)),
            new Uint8(targetSoc),
            new Uint256(timeoutSeconds)
        ));

        return deployRemoteCall(
            ChargingEscrowContract.class,
            web3j,
            transactionManager,
            gasProvider,
            BINARY,
            encodedConstructor
        );
    }

    public static ChargingEscrowContract load(String address,
                                              Web3j web3j,
                                              Credentials credentials,
                                              ContractGasProvider gasProvider) {
        return new ChargingEscrowContract(address, web3j, credentials, gasProvider);
    }

    public static ChargingEscrowContract load(String address,
                                              Web3j web3j,
                                              TransactionManager transactionManager,
                                              ContractGasProvider gasProvider) {
        return new ChargingEscrowContract(address, web3j, transactionManager, gasProvider);
    }

    public RemoteCall<TransactionReceipt> deposit(BigInteger weiAmount) {
        final Function function = new Function(
            "deposit",
            Collections.emptyList(),
            Collections.emptyList()
        );
        return executeRemoteCallTransaction(function, weiAmount);
    }

    public RemoteCall<TransactionReceipt> verifyStation(byte[] liveHash) {
        final Function function = new Function(
            "verifyStation",
            Arrays.asList(new Bytes32(normalizeBytes32(liveHash))),
            Collections.emptyList()
        );
        return executeRemoteCallTransaction(function);
    }

    public RemoteCall<TransactionReceipt> startCharging(String sessionId) {
        final Function function = new Function(
            "startCharging",
            Arrays.asList(new Utf8String(sessionId)),
            Collections.emptyList()
        );
        return executeRemoteCallTransaction(function);
    }

    public RemoteCall<TransactionReceipt> updateSoc(BigInteger soc) {
        final Function function = new Function(
            "updateSoc",
            Arrays.asList(new Uint8(soc)),
            Collections.emptyList()
        );
        return executeRemoteCallTransaction(function);
    }

    public RemoteCall<TransactionReceipt> completeSession() {
        final Function function = new Function(
            "completeSession",
            Collections.emptyList(),
            Collections.emptyList()
        );
        return executeRemoteCallTransaction(function);
    }

    public RemoteCall<TransactionReceipt> releaseFunds() {
        final Function function = new Function(
            "releaseFunds",
            Collections.emptyList(),
            Collections.emptyList()
        );
        return executeRemoteCallTransaction(function);
    }

    public RemoteCall<TransactionReceipt> refund(String reason) {
        final Function function = new Function(
            "refund",
            Arrays.asList(new Utf8String(reason)),
            Collections.emptyList()
        );
        return executeRemoteCallTransaction(function);
    }

    public RemoteCall<TransactionReceipt> cancel(String reason) {
        final Function function = new Function(
            "cancel",
            Arrays.asList(new Utf8String(reason)),
            Collections.emptyList()
        );
        return executeRemoteCallTransaction(function);
    }

    public RemoteCall<BigInteger> getState() {
        final Function function = new Function(
            "getState",
            Collections.emptyList(),
            Collections.singletonList(new TypeReference<Uint8>() { })
        );
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteCall<BigInteger> getBalance() {
        final Function function = new Function(
            "getBalance",
            Collections.emptyList(),
            Collections.singletonList(new TypeReference<Uint256>() { })
        );
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    private static byte[] normalizeBytes32(byte[] value) {
        byte[] normalized = new byte[32];
        if (value == null || value.length == 0) {
            return normalized;
        }

        int bytesToCopy = Math.min(value.length, 32);
        System.arraycopy(
            value,
            value.length - bytesToCopy,
            normalized,
            32 - bytesToCopy,
            bytesToCopy
        );
        return normalized;
    }

    private static String loadBinaryFromResources() {
        try (InputStream input = ChargingEscrowContract.class
            .getClassLoader()
            .getResourceAsStream("solidity/ChargingEscrow.bin")) {
            if (input == null) {
                throw new IllegalStateException("Missing resource solidity/ChargingEscrow.bin");
            }

            String bytecode = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (bytecode.isEmpty()) {
                throw new IllegalStateException("Resource solidity/ChargingEscrow.bin is empty");
            }

            return bytecode.startsWith("0x") ? bytecode : "0x" + bytecode;
        } catch (Exception ex) {
            throw new ExceptionInInitializerError("Failed to load ChargingEscrow bytecode: " + ex.getMessage());
        }
    }
}
