package com.cybersecuals.gridgarrison.trust.contract.generated;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.protocol.core.RemoteFunctionCall;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.Contract;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;

/**
 * <p>Auto generated code.
 * <p><strong>Do not modify!</strong>
 * <p>Please use the <a href="https://docs.web3j.io/command_line.html">web3j command line tools</a>,
 * or the org.web3j.codegen.SolidityFunctionWrapperGenerator in the 
 * <a href="https://github.com/web3j/web3j/tree/master/codegen">codegen module</a> to update.
 *
 * <p>Generated with web3j version 4.10.3.
 */
@SuppressWarnings("rawtypes")
public class FirmwareRegistry extends Contract {
    public static final String BINARY = "6080604052348015600e575f5ffd5b5061044f8061001c5f395ff3fe608060405234801561000f575f5ffd5b5060043610610034575f3560e01c8063c294902a14610038578063dcb61c921461004d575b5f5ffd5b61004b6100463660046101f1565b610076565b005b61006061005b366004610256565b6100a5565b60405161006d9190610290565b60405180910390f35b805f8360405161008691906102c5565b908152602001604051809103902090816100a0919061035e565b505050565b60605f826040516100b691906102c5565b908152602001604051809103902080546100cf906102db565b80601f01602080910402602001604051908101604052809291908181526020018280546100fb906102db565b80156101465780601f1061011d57610100808354040283529160200191610146565b820191905f5260205f20905b81548152906001019060200180831161012957829003601f168201915b50505050509050919050565b634e487b7160e01b5f52604160045260245ffd5b5f82601f830112610175575f5ffd5b813567ffffffffffffffff81111561018f5761018f610152565b604051601f8201601f19908116603f0116810167ffffffffffffffff811182821017156101be576101be610152565b6040528181528382016020018510156101d5575f5ffd5b816020850160208301375f918101602001919091529392505050565b5f5f60408385031215610202575f5ffd5b823567ffffffffffffffff811115610218575f5ffd5b61022485828601610166565b925050602083013567ffffffffffffffff811115610240575f5ffd5b61024c85828601610166565b9150509250929050565b5f60208284031215610266575f5ffd5b813567ffffffffffffffff81111561027c575f5ffd5b61028884828501610166565b949350505050565b602081525f82518060208401528060208501604085015e5f604082850101526040601f19601f83011684010191505092915050565b5f82518060208501845e5f920191825250919050565b600181811c908216806102ef57607f821691505b60208210810361030d57634e487b7160e01b5f52602260045260245ffd5b50919050565b601f8211156100a057805f5260205f20601f840160051c810160208510156103385750805b601f840160051c820191505b81811015610357575f8155600101610344565b5050505050565b815167ffffffffffffffff81111561037857610378610152565b61038c8161038684546102db565b84610313565b6020601f8211600181146103be575f83156103a75750848201515b5f19600385901b1c1916600184901b178455610357565b5f84815260208120601f198516915b828110156103ed57878501518255602094850194600190920191016103cd565b508482101561040a57868401515f19600387901b60f8161c191681555b50505050600190811b0190555056fea26469706673582212209ce881714c10429293d4fcb82ad1e7f9ab58beeccd38955e325c7b235fddd66564736f6c634300081e0033";

    public static final String FUNC_GETGOLDENHASH = "getGoldenHash";

    public static final String FUNC_REGISTERGOLDENHASH = "registerGoldenHash";

    @Deprecated
    protected FirmwareRegistry(String contractAddress, Web3j web3j, Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        super(BINARY, contractAddress, web3j, credentials, gasPrice, gasLimit);
    }

    protected FirmwareRegistry(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, credentials, contractGasProvider);
    }

    @Deprecated
    protected FirmwareRegistry(String contractAddress, Web3j web3j, TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        super(BINARY, contractAddress, web3j, transactionManager, gasPrice, gasLimit);
    }

    protected FirmwareRegistry(String contractAddress, Web3j web3j, TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public RemoteFunctionCall<String> getGoldenHash(String chargerId) {
        final Function function = new Function(FUNC_GETGOLDENHASH, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Utf8String(chargerId)), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Utf8String>() {}));
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    public RemoteFunctionCall<TransactionReceipt> registerGoldenHash(String chargerId, String firmwareHash) {
        final Function function = new Function(
                FUNC_REGISTERGOLDENHASH, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.Utf8String(chargerId), 
                new org.web3j.abi.datatypes.Utf8String(firmwareHash)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    @Deprecated
    public static FirmwareRegistry load(String contractAddress, Web3j web3j, Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        return new FirmwareRegistry(contractAddress, web3j, credentials, gasPrice, gasLimit);
    }

    @Deprecated
    public static FirmwareRegistry load(String contractAddress, Web3j web3j, TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        return new FirmwareRegistry(contractAddress, web3j, transactionManager, gasPrice, gasLimit);
    }

    public static FirmwareRegistry load(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        return new FirmwareRegistry(contractAddress, web3j, credentials, contractGasProvider);
    }

    public static FirmwareRegistry load(String contractAddress, Web3j web3j, TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return new FirmwareRegistry(contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public static RemoteCall<FirmwareRegistry> deploy(Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        return deployRemoteCall(FirmwareRegistry.class, web3j, credentials, contractGasProvider, BINARY, "");
    }

    @Deprecated
    public static RemoteCall<FirmwareRegistry> deploy(Web3j web3j, Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        return deployRemoteCall(FirmwareRegistry.class, web3j, credentials, gasPrice, gasLimit, BINARY, "");
    }

    public static RemoteCall<FirmwareRegistry> deploy(Web3j web3j, TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return deployRemoteCall(FirmwareRegistry.class, web3j, transactionManager, contractGasProvider, BINARY, "");
    }

    @Deprecated
    public static RemoteCall<FirmwareRegistry> deploy(Web3j web3j, TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        return deployRemoteCall(FirmwareRegistry.class, web3j, transactionManager, gasPrice, gasLimit, BINARY, "");
    }
}
