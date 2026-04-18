package com.cybersecuals.gridgarrison.trust.contract;

import io.reactivex.Flowable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

    public static final String BINARY = "0x608060405234801561001057600080fd5b50610edb806100206000396000f3fe608060405234801561001057600080fd5b50600436106100575760003560e01c806331e4c6a21461005c5780639876e61814610078578063c294902a14610094578063dcb61c92146100b0578063e4a2de1e146100e0575b600080fd5b61007660048036038101906100719190610704565b610110565b005b610092600480360381019061008d91906107ab565b610150565b005b6100ae60048036038101906100a99190610882565b61020f565b005b6100ca60048036038101906100c591906108fa565b6102e8565b6040516100d791906109c2565b60405180910390f35b6100fa60048036038101906100f591906108fa565b61039b565b6040516101079190610a8c565b60405180910390f35b7f050c851ae99504eecc6d4ce0cea11c4e1d4d37a7b962aefe72780cd0ac60288183838360405161014393929190610aae565b60405180910390a1505050565b60405180606001604052808481526020018381526020018281525060008560405161017b9190610b36565b9081526020016040518091039020600082015181600001908161019e9190610d63565b5060208201518160010190816101b49190610d63565b5060408201518160020190816101ca9190610d63565b509050507f010efa741dd93df8f478602c2cd7b9e3fd2c780c2083416749f38377cdade32084848360405161020193929190610aae565b60405180910390a150505050565b6040518060600160405280828152602001604051806020016040528060008152508152602001604051806020016040528060008152508152506000836040516102589190610b36565b9081526020016040518091039020600082015181600001908161027b9190610d63565b5060208201518160010190816102919190610d63565b5060408201518160020190816102a79190610d63565b509050507f010efa741dd93df8f478602c2cd7b9e3fd2c780c2083416749f38377cdade32082826040516102dc929190610e5b565b60405180910390a15050565b60606000826040516102fa9190610b36565b9081526020016040518091039020600001805461031690610b7c565b80601f016020809104026020016040519081016040528092919081815260200182805461034290610b7c565b801561038f5780601f106103645761010080835404028352916020019161038f565b820191906000526020600020905b81548152906001019060200180831161037257829003601f168201915b50505050509050919050565b6103a3610589565b6000826040516103b39190610b36565b90815260200160405180910390206040518060600160405290816000820180546103dc90610b7c565b80601f016020809104026020016040519081016040528092919081815260200182805461040890610b7c565b80156104555780601f1061042a57610100808354040283529160200191610455565b820191906000526020600020905b81548152906001019060200180831161043857829003601f168201915b5050505050815260200160018201805461046e90610b7c565b80601f016020809104026020016040519081016040528092919081815260200182805461049a90610b7c565b80156104e75780601f106104bc576101008083540402835291602001916104e7565b820191906000526020600020905b8154815290600101906020018083116104ca57829003601f168201915b5050505050815260200160028201805461050090610b7c565b80601f016020809104026020016040519081016040528092919081815260200182805461052c90610b7c565b80156105795780601f1061054e57610100808354040283529160200191610579565b820191906000526020600020905b81548152906001019060200180831161055c57829003601f168201915b5050505050815250509050919050565b60405180606001604052806060815260200160608152602001606081525090565b6000604051905090565b600080fd5b600080fd5b600080fd5b600080fd5b6000601f19601f8301169050919050565b7f4e487b7100000000000000000000000000000000000000000000000000000000600052604160045260246000fd5b610611826105c8565b810181811067ffffffffffffffff821117156106305761062f6105d9565b5b80604052505050565b60006106436105aa565b905061064f8282610608565b919050565b600067ffffffffffffffff82111561066f5761066e6105d9565b5b610678826105c8565b9050602081019050919050565b82818337600083830152505050565b60006106a76106a284610654565b610639565b9050828152602081018484840111156106c3576106c26105c3565b5b6106ce848285610685565b509392505050565b600082601f8301126106eb576106ea6105be565b5b81356106fb848260208601610694565b91505092915050565b60008060006060848603121561071d5761071c6105b4565b5b600084013567ffffffffffffffff81111561073b5761073a6105b9565b5b610747868287016106d6565b935050602084013567ffffffffffffffff811115610768576107676105b9565b5b610774868287016106d6565b925050604084013567ffffffffffffffff811115610795576107946105b9565b5b6107a1868287016106d6565b9150509250925092565b600080600080608085870312156107c5576107c46105b4565b5b600085013567ffffffffffffffff8111156107e3576107e26105b9565b5b6107ef878288016106d6565b945050602085013567ffffffffffffffff8111156108105761080f6105b9565b5b61081c878288016106d6565b935050604085013567ffffffffffffffff81111561083d5761083c6105b9565b5b610849878288016106d6565b925050606085013567ffffffffffffffff81111561086a576108696105b9565b5b610876878288016106d6565b91505092959194509250565b60008060408385031215610899576108986105b4565b5b600083013567ffffffffffffffff8111156108b7576108b66105b9565b5b6108c3858286016106d6565b925050602083013567ffffffffffffffff8111156108e4576108e36105b9565b5b6108f0858286016106d6565b9150509250929050565b6000602082840312156109105761090f6105b4565b5b600082013567ffffffffffffffff81111561092e5761092d6105b9565b5b61093a848285016106d6565b91505092915050565b600081519050919050565b600082825260208201905092915050565b60005b8381101561097d578082015181840152602081019050610962565b60008484015250505050565b600061099482610943565b61099e818561094e565b93506109ae81856020860161095f565b6109b7816105c8565b840191505092915050565b600060208201905081810360008301526109dc8184610989565b905092915050565b600082825260208201905092915050565b6000610a0082610943565b610a0a81856109e4565b9350610a1a81856020860161095f565b610a23816105c8565b840191505092915050565b60006060830160008301518482036000860152610a4b82826109f5565b91505060208301518482036020860152610a6582826109f5565b91505060408301518482036040860152610a7f82826109f5565b9150508091505092915050565b60006020820190508181036000830152610aa68184610a2e565b905092915050565b60006060820190508181036000830152610ac88186610989565b90508181036020830152610adc8185610989565b90508181036040830152610af08184610989565b9050949350505050565b600081905092915050565b6000610b1082610943565b610b1a8185610afa565b9350610b2a81856020860161095f565b80840191505092915050565b6000610b428284610b05565b915081905092915050565b7f4e487b7100000000000000000000000000000000000000000000000000000000600052602260045260246000fd5b60006002820490506001821680610b9457607f821691505b602082108103610ba757610ba6610b4d565b5b50919050565b60008190508160005260206000209050919050565b60006020601f8301049050919050565b600082821b905092915050565b600060088302610c0f7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff82610bd2565b610c198683610bd2565b95508019841693508086168417925050509392505050565b6000819050919050565b6000819050919050565b6000610c60610c5b610c5684610c31565b610c3b565b610c31565b9050919050565b6000819050919050565b610c7a83610c45565b610c8e610c8682610c67565b848454610bdf565b825550505050565b600090565b610ca3610c96565b610cae818484610c71565b505050565b5b81811015610cd257610cc7600082610c9b565b600181019050610cb4565b5050565b601f821115610d1757610ce881610bad565b610cf184610bc2565b81016020851015610d00578190505b610d14610d0c85610bc2565b830182610cb3565b50505b505050565b600082821c905092915050565b6000610d3a60001984600802610d1c565b1980831691505092915050565b6000610d538383610d29565b9150826002028217905092915050565b610d6c82610943565b67ffffffffffffffff811115610d8557610d846105d9565b5b610d8f8254610b7c565b610d9a828285610cd6565b600060209050601f831160018114610dcd5760008415610dbb578287015190505b610dc58582610d47565b865550610e2d565b601f198416610ddb86610bad565b60005b82811015610e0357848901518255600182019150602085019450602081019050610dde565b86831015610e205784890151610e1c601f891682610d29565b8355505b6001600288020188555050505b505050505050565b50565b6000610e4560008361094e565b9150610e5082610e35565b600082019050919050565b60006060820190508181036000830152610e758185610989565b90508181036020830152610e898184610989565b90508181036040830152610e9c81610e38565b9050939250505056fea264697066735822122073af964eafb77fb01dd5ce029f1172537189e4f0dd0b607529de817773bb2a3b64736f6c63430008110033";

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
                Arrays.<TypeReference<?>>asList(new TypeReference<SignedGoldenRecord>() {}));
        return executeRemoteCallSingleValueReturn(function, SignedGoldenRecord.class);
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

        public SignedGoldenRecord(String goldenHash, String manufacturerSignature, String manufacturerId) {
            super(new Utf8String(goldenHash), new Utf8String(manufacturerSignature), new Utf8String(manufacturerId));
            this.goldenHash = goldenHash;
            this.manufacturerSignature = manufacturerSignature;
            this.manufacturerId = manufacturerId;
        }

        public SignedGoldenRecord(Utf8String goldenHash, Utf8String manufacturerSignature, Utf8String manufacturerId) {
            super(goldenHash, manufacturerSignature, manufacturerId);
            this.goldenHash = goldenHash.getValue();
            this.manufacturerSignature = manufacturerSignature.getValue();
            this.manufacturerId = manufacturerId.getValue();
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