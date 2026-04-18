package com.cybersecuals.gridgarrison.trust.contract;

import io.reactivex.Flowable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.DynamicStruct;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.protocol.core.RemoteFunctionCall;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.BaseEventResponse;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.Contract;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;

/**
 * Web3j wrapper for FirmwareRegistry.sol.
 *
 * Renamed FirmwareRegistry → FirmwareRegistryContract to match BlockchainServiceImpl.
 * Fix applied: removed "javax.annotation.processing.Generated" import which
 * does not exist on the compile classpath in Java 17 + Spring Boot 3.x.
 */
@SuppressWarnings("rawtypes")
public class FirmwareRegistryContract extends Contract {

    public static final String BINARY = "608060405234801561001057600080fd5b50336000806101000a81548173ffffffffffffffffffffffffffffffffffffffff021916908373ffffffffffffffffffffffffffffffffffffffff160217905550611300806100606000396000f3fe608060405234801561001057600080fd5b50600436106100625760003560e01c806331e4c6a2146100675780638da5cb5b146100835780639876e618146100a1578063c294902a146100bd578063dcb61c92146100d9578063e4a2de1e14610109575b600080fd5b610081600480360381019061007c9190610a73565b61013c565b005b61008b610235565b6040516100989190610b5b565b60405180910390f35b6100bb60048036038101906100b69190610b76565b610259565b005b6100d760048036038101906100d29190610c4d565b610447565b005b6100f360048036038101906100ee9190610cc5565b61064f565b6040516101009190610d8d565b60405180910390f35b610123600480360381019061011e9190610cc5565b610702565b6040516101339493929190610dc8565b60405180910390f35b60008054906101000a900473ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff163373ffffffffffffffffffffffffffffffffffffffff16146101ca576040517f08c379a00000000000000000000000000000000000000000000000000000000081526004016101c190610e94565b60405180910390fd5b3373ffffffffffffffffffffffffffffffffffffffff16836040516101ef9190610ef0565b60405180910390207f11dfaedadf44ffb3aeb23135b017363fec745aad76b077c8bb56b7d24bd55dfe8484604051610228929190610f07565b60405180910390a3505050565b60008054906101000a900473ffffffffffffffffffffffffffffffffffffffff1681565b60008054906101000a900473ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff163373ffffffffffffffffffffffffffffffffffffffff16146102e7576040517f08c379a00000000000000000000000000000000000000000000000000000000081526004016102de90610e94565b60405180910390fd5b6040518060800160405280848152602001838152602001828152602001428152506001856040516103189190610ef0565b9081526020016040518091039020600082015181600001908161033b919061114a565b506020820151816001019081610351919061114a565b506040820151816002019081610367919061114a565b50606082015181600301559050503373ffffffffffffffffffffffffffffffffffffffff168460405161039a9190610ef0565b60405180910390207f5d518331dab9ef4de7023c73b62313478d0f55b2c4e40fb25335bcdebb95ed4b856040516103d19190610d8d565b60405180910390a33373ffffffffffffffffffffffffffffffffffffffff16846040516103fe9190610ef0565b60405180910390207f16c2c3b42beb53eb623bb1f22686b36e24bf20a8594b9d2e8125a9407ad4970c8584426040516104399392919061121c565b60405180910390a350505050565b60008054906101000a900473ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff163373ffffffffffffffffffffffffffffffffffffffff16146104d5576040517f08c379a00000000000000000000000000000000000000000000000000000000081526004016104cc90610e94565b60405180910390fd5b6040518060800160405280828152602001604051806020016040528060008152508152602001604051806020016040528060008152508152602001428152506001836040516105249190610ef0565b90815260200160405180910390206000820151816000019081610547919061114a565b50602082015181600101908161055d919061114a565b506040820151816002019081610573919061114a565b50606082015181600301559050503373ffffffffffffffffffffffffffffffffffffffff16826040516105a69190610ef0565b60405180910390207f5d518331dab9ef4de7023c73b62313478d0f55b2c4e40fb25335bcdebb95ed4b836040516105dd9190610d8d565b60405180910390a33373ffffffffffffffffffffffffffffffffffffffff168260405161060a9190610ef0565b60405180910390207f16c2c3b42beb53eb623bb1f22686b36e24bf20a8594b9d2e8125a9407ad4970c8342604051610643929190611287565b60405180910390a35050565b60606001826040516106619190610ef0565b9081526020016040518091039020600001805461067d90610f6d565b80601f01602080910402602001604051908101604052809291908181526020018280546106a990610f6d565b80156106f65780601f106106cb576101008083540402835291602001916106f6565b820191906000526020600020905b8154815290600101906020018083116106d957829003601f168201915b50505050509050919050565b606080606060008060018660405161071a9190610ef0565b908152602001604051809103902060405180608001604052908160008201805461074390610f6d565b80601f016020809104026020016040519081016040528092919081815260200182805461076f90610f6d565b80156107bc5780601f10610791576101008083540402835291602001916107bc565b820191906000526020600020905b81548152906001019060200180831161079f57829003601f168201915b505050505081526020016001820180546107d590610f6d565b80601f016020809104026020016040519081016040528092919081815260200182805461080190610f6d565b801561084e5780601f106108235761010080835404028352916020019161084e565b820191906000526020600020905b81548152906001019060200180831161083157829003601f168201915b5050505050815260200160028201805461086790610f6d565b80601f016020809104026020016040519081016040528092919081815260200182805461089390610f6d565b80156108e05780601f106108b5576101008083540402835291602001916108e0565b820191906000526020600020905b8154815290600101906020018083116108c357829003601f168201915b50505050508152602001600382015481525050905080600001518160200151826040015183606001519450945094509450509193509193565b6000604051905090565b600080fd5b600080fd5b600080fd5b600080fd5b6000601f19601f8301169050919050565b7f4e487b7100000000000000000000000000000000000000000000000000000000600052604160045260246000fd5b61098082610937565b810181811067ffffffffffffffff8211171561099f5761099e610948565b5b80604052505050565b60006109b2610919565b90506109be8282610977565b919050565b600067ffffffffffffffff8211156109de576109dd610948565b5b6109e782610937565b9050602081019050919050565b82818337600083830152505050565b6000610a16610a11846109c3565b6109a8565b905082815260208101848484011115610a3257610a31610932565b5b610a3d8482856109f4565b509392505050565b600082601f830112610a5a57610a5961092d565b5b8135610a6a848260208601610a03565b91505092915050565b600080600060608486031215610a8c57610a8b610923565b5b600084013567ffffffffffffffff811115610aaa57610aa9610928565b5b610ab686828701610a45565b935050602084013567ffffffffffffffff811115610ad757610ad6610928565b5b610ae386828701610a45565b925050604084013567ffffffffffffffff811115610b0457610b03610928565b5b610b1086828701610a45565b9150509250925092565b600073ffffffffffffffffffffffffffffffffffffffff82169050919050565b6000610b4582610b1a565b9050919050565b610b5581610b3a565b82525050565b6000602082019050610b706000830184610b4c565b92915050565b60008060008060808587031215610b9057610b8f610923565b5b600085013567ffffffffffffffff811115610bae57610bad610928565b5b610bba87828801610a45565b945050602085013567ffffffffffffffff811115610bdb57610bda610928565b5b610be787828801610a45565b935050604085013567ffffffffffffffff811115610c0857610c07610928565b5b610c1487828801610a45565b925050606085013567ffffffffffffffff811115610c3557610c34610928565b5b610c4187828801610a45565b91505092959194509250565b60008060408385031215610c6457610c63610923565b5b600083013567ffffffffffffffff811115610c8257610c81610928565b5b610c8e85828601610a45565b925050602083013567ffffffffffffffff811115610caf57610cae610928565b5b610cbb85828601610a45565b9150509250929050565b600060208284031215610cdb57610cda610923565b5b600082013567ffffffffffffffff811115610cf957610cf8610928565b5b610d0584828501610a45565b91505092915050565b600081519050919050565b600082825260208201905092915050565b60005b83811015610d48578082015181840152602081019050610d2d565b60008484015250505050565b6000610d5f82610d0e565b610d698185610d19565b9350610d79818560208601610d2a565b610d8281610937565b840191505092915050565b60006020820190508181036000830152610da78184610d54565b905092915050565b6000819050919050565b610dc281610daf565b82525050565b60006080820190508181036000830152610de28187610d54565b90508181036020830152610df68186610d54565b90508181036040830152610e0a8185610d54565b9050610e196060830184610db9565b95945050505050565b7f4669726d7761726552656769737472793a2063616c6c6572206973206e6f742060008201527f746865206f776e65720000000000000000000000000000000000000000000000602082015250565b6000610e7e602983610d19565b9150610e8982610e22565b604082019050919050565b60006020820190508181036000830152610ead81610e71565b9050919050565b600081905092915050565b6000610eca82610d0e565b610ed48185610eb4565b9350610ee4818560208601610d2a565b80840191505092915050565b6000610efc8284610ebf565b915081905092915050565b60006040820190508181036000830152610f218185610d54565b90508181036020830152610f358184610d54565b90509392505050565b7f4e487b7100000000000000000000000000000000000000000000000000000000600052602260045260246000fd5b60006002820490506001821680610f8557607f821691505b602082108103610f9857610f97610f3e565b5b50919050565b60008190508160005260206000209050919050565b60006020601f8301049050919050565b600082821b905092915050565b6000600883026110007fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff82610fc3565b61100a8683610fc3565b95508019841693508086168417925050509392505050565b6000819050919050565b600061104761104261103d84610daf565b611022565b610daf565b9050919050565b6000819050919050565b6110618361102c565b61107561106d8261104e565b848454610fd0565b825550505050565b600090565b61108a61107d565b611095818484611058565b505050565b5b818110156110b9576110ae600082611082565b60018101905061109b565b5050565b601f8211156110fe576110cf81610f9e565b6110d884610fb3565b810160208510156110e7578190505b6110fb6110f385610fb3565b83018261109a565b50505b505050565b600082821c905092915050565b600061112160001984600802611103565b1980831691505092915050565b600061113a8383611110565b9150826002028217905092915050565b61115382610d0e565b67ffffffffffffffff81111561116c5761116b610948565b5b6111768254610f6d565b6111818282856110bd565b600060209050601f8311600181146111b457600084156111a2578287015190505b6111ac858261112e565b865550611214565b601f1984166111c286610f9e565b60005b828110156111ea578489015182556001820191506020850194506020810190506111c5565b868310156112075784890151611203601f891682611110565b8355505b6001600288020188555050505b505050505050565b600060608201905081810360008301526112368186610d54565b9050818103602083015261124a8185610d54565b90506112596040830184610db9565b949350505050565b50565b6000611271600083610d19565b915061127c82611261565b600082019050919050565b600060608201905081810360008301526112a18185610d54565b905081810360208301526112b481611264565b90506112c36040830184610db9565b939250505056fea2646970667358221220c2b5b9d7cae7a889ffd189fb8a974405377e7389d35a7c351d787bce38c27acf64736f6c63430008130033";

    private static String librariesLinkedBinary;

    public static final String FUNC_REGISTERGOLDENHASH      = "registerGoldenHash";
    public static final String FUNC_REGISTERSIGNEDGOLDENHASH = "registerSignedGoldenHash";
    public static final String FUNC_GETGOLDENHASH            = "getGoldenHash";
    public static final String FUNC_GETSIGNEDGOLDENRECORD   = "getSignedGoldenRecord";
    public static final String FUNC_RECORDSESSIONEVENT      = "recordSessionEvent";

    public static final Event GOLDENHASHREGISTERED_EVENT = new Event("GoldenHashRegistered",
            Arrays.<TypeReference<?>>asList(
                new TypeReference<Utf8String>() {},
                new TypeReference<Utf8String>() {},
                new TypeReference<Utf8String>() {}));
    public static final Event SESSIONEVENTRECORDED_EVENT = new Event("SessionEventRecorded",
            Arrays.<TypeReference<?>>asList(
                new TypeReference<Utf8String>() {},
                new TypeReference<Utf8String>() {},
                new TypeReference<Utf8String>() {}));

    protected static final HashMap<String, String> _addresses;
    static { _addresses = new HashMap<>(); }

    protected FirmwareRegistryContract(String contractAddress, Web3j web3j,
            Credentials credentials, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, credentials, contractGasProvider);
    }

    protected FirmwareRegistryContract(String contractAddress, Web3j web3j,
            TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public static FirmwareRegistryContract load(String contractAddress, Web3j web3j,
            Credentials credentials, ContractGasProvider contractGasProvider) {
        return new FirmwareRegistryContract(contractAddress, web3j, credentials, contractGasProvider);
    }

    public static FirmwareRegistryContract load(String contractAddress, Web3j web3j,
            TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return new FirmwareRegistryContract(contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public static RemoteCall<FirmwareRegistryContract> deploy(Web3j web3j, Credentials credentials,
            ContractGasProvider contractGasProvider) {
        return deployRemoteCall(FirmwareRegistryContract.class, web3j, credentials,
                contractGasProvider, getDeploymentBinary(), "");
    }

    public static RemoteCall<FirmwareRegistryContract> deploy(Web3j web3j,
            TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return deployRemoteCall(FirmwareRegistryContract.class, web3j, transactionManager,
                contractGasProvider, getDeploymentBinary(), "");
    }

    public RemoteFunctionCall<TransactionReceipt> registerGoldenHash(String stationId, String goldenHash) {
        final Function function = new Function(FUNC_REGISTERGOLDENHASH,
                Arrays.<Type>asList(new Utf8String(stationId), new Utf8String(goldenHash)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> registerSignedGoldenHash(String stationId,
            String goldenHash, String manufacturerSignature, String manufacturerId) {
        final Function function = new Function(FUNC_REGISTERSIGNEDGOLDENHASH,
                Arrays.<Type>asList(
                    new Utf8String(stationId), new Utf8String(goldenHash),
                    new Utf8String(manufacturerSignature), new Utf8String(manufacturerId)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<String> getGoldenHash(String stationId) {
        final Function function = new Function(FUNC_GETGOLDENHASH,
                Arrays.<Type>asList(new Utf8String(stationId)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Utf8String>() {}));
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    public RemoteFunctionCall<SignedGoldenRecord> getSignedGoldenRecord(String stationId) {
        final Function function = new Function(FUNC_GETSIGNEDGOLDENRECORD,
                Arrays.<Type>asList(new Utf8String(stationId)),
                Arrays.<TypeReference<?>>asList(
                    new TypeReference<Utf8String>() {},
                    new TypeReference<Utf8String>() {},
                    new TypeReference<Utf8String>() {},
                    new TypeReference<org.web3j.abi.datatypes.generated.Uint256>() {}));
        return new RemoteFunctionCall<>(function, new Callable<>() {
            @Override
            @SuppressWarnings("rawtypes")
            public SignedGoldenRecord call() throws Exception {
                List<Type> values = executeCallMultipleValueReturn(function);
                return new SignedGoldenRecord(
                    values.get(0).getValue().toString(),
                    values.get(1).getValue().toString(),
                    values.get(2).getValue().toString(),
                    (BigInteger) values.get(3).getValue()
                );
            }
        });
    }

    public RemoteFunctionCall<TransactionReceipt> recordSessionEvent(String stationId,
            String sessionId, String state) {
        final Function function = new Function(FUNC_RECORDSESSIONEVENT,
                Arrays.<Type>asList(
                    new Utf8String(stationId), new Utf8String(sessionId), new Utf8String(state)),
                Collections.emptyList());
        return executeRemoteCallTransaction(function);
    }

    public static List<GoldenHashRegisteredEventResponse> getGoldenHashRegisteredEvents(
            TransactionReceipt transactionReceipt) {
        List<Contract.EventValuesWithLog> valueList = staticExtractEventParametersWithLog(
                GOLDENHASHREGISTERED_EVENT, transactionReceipt);
        ArrayList<GoldenHashRegisteredEventResponse> responses = new ArrayList<>(valueList.size());
        for (Contract.EventValuesWithLog eventValues : valueList) {
            GoldenHashRegisteredEventResponse typedResponse = new GoldenHashRegisteredEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.stationId     = (String) eventValues.getNonIndexedValues().get(0).getValue();
            typedResponse.goldenHash    = (String) eventValues.getNonIndexedValues().get(1).getValue();
            typedResponse.manufacturerId= (String) eventValues.getNonIndexedValues().get(2).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public Flowable<GoldenHashRegisteredEventResponse> goldenHashRegisteredEventFlowable(
            DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(GOLDENHASHREGISTERED_EVENT));
        return web3j.ethLogFlowable(filter).map(log -> {
            Contract.EventValuesWithLog eventValues = extractEventParametersWithLog(GOLDENHASHREGISTERED_EVENT, log);
            GoldenHashRegisteredEventResponse typedResponse = new GoldenHashRegisteredEventResponse();
            typedResponse.log = log;
            typedResponse.stationId     = (String) eventValues.getNonIndexedValues().get(0).getValue();
            typedResponse.goldenHash    = (String) eventValues.getNonIndexedValues().get(1).getValue();
            typedResponse.manufacturerId= (String) eventValues.getNonIndexedValues().get(2).getValue();
            return typedResponse;
        });
    }

    private static String getDeploymentBinary() {
        return librariesLinkedBinary != null ? librariesLinkedBinary : BINARY;
    }

    protected String getStaticDeployedAddress(String networkId) { return _addresses.get(networkId); }
    public static String getPreviouslyDeployedAddress(String networkId) { return _addresses.get(networkId); }

    public static class SignedGoldenRecord extends DynamicStruct {
        public String goldenHash;
        public String manufacturerSignature;
        public String manufacturerId;
        public BigInteger registeredAt;

        public SignedGoldenRecord(String goldenHash,
                                  String manufacturerSignature,
                                  String manufacturerId,
                                  BigInteger registeredAt) {
            super(new Utf8String(goldenHash),
                  new Utf8String(manufacturerSignature),
                  new Utf8String(manufacturerId),
                  new org.web3j.abi.datatypes.generated.Uint256(registeredAt));
            this.goldenHash = goldenHash;
            this.manufacturerSignature = manufacturerSignature;
            this.manufacturerId = manufacturerId;
            this.registeredAt = registeredAt;
        }

        public SignedGoldenRecord(Utf8String goldenHash,
                                  Utf8String manufacturerSignature,
                                  Utf8String manufacturerId,
                                  org.web3j.abi.datatypes.generated.Uint256 registeredAt) {
            super(goldenHash, manufacturerSignature, manufacturerId, registeredAt);
            this.goldenHash = goldenHash.getValue();
            this.manufacturerSignature = manufacturerSignature.getValue();
            this.manufacturerId = manufacturerId.getValue();
            this.registeredAt = registeredAt.getValue();
        }

        public String goldenHash() {
            return goldenHash;
        }

        public String manufacturerSignature() {
            return manufacturerSignature;
        }

        public String manufacturerId() {
            return manufacturerId;
        }

        public BigInteger registeredAt() {
            return registeredAt;
        }
    }

    public static class GoldenHashRegisteredEventResponse extends BaseEventResponse {
        public String stationId;
        public String goldenHash;
        public String manufacturerId;
    }

    public static class SessionEventRecordedEventResponse extends BaseEventResponse {
        public String stationId;
        public String sessionId;
        public String state;
    }
}


