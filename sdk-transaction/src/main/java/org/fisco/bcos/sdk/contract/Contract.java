/*
 * Copyright 2014-2020  [fisco-dev]
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package org.fisco.bcos.sdk.contract;

import org.fisco.bcos.sdk.abi.*;
import org.fisco.bcos.sdk.abi.datatypes.Address;
import org.fisco.bcos.sdk.abi.datatypes.Event;
import org.fisco.bcos.sdk.abi.datatypes.Function;
import org.fisco.bcos.sdk.abi.datatypes.Type;
import org.fisco.bcos.sdk.client.Client;
import org.fisco.bcos.sdk.client.protocol.response.Call;
import org.fisco.bcos.sdk.crypto.CryptoSuite;
import org.fisco.bcos.sdk.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.model.RetCode;
import org.fisco.bcos.sdk.model.TransactionReceipt;
import org.fisco.bcos.sdk.model.callback.TransactionCallback;
import org.fisco.bcos.sdk.transaction.codec.decode.ReceiptParser;
import org.fisco.bcos.sdk.transaction.manager.TransactionProcessor;
import org.fisco.bcos.sdk.transaction.manager.TransactionProcessorFactory;
import org.fisco.bcos.sdk.transaction.model.dto.CallRequest;
import org.fisco.bcos.sdk.transaction.model.exception.ContractException;
import org.fisco.bcos.sdk.utils.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Contract help manage all operations including deploy, send transaction, call contract, and
 * subscribe event of one specific contract. It is inherited by precompiled contracts and contract
 * java wrappers.
 */
public class Contract {
    protected static Logger logger = LoggerFactory.getLogger(Contract.class);
    protected final String contractBinary;
    protected String contractAddress;
    // transactionReceipt after deploying the contract
    protected TransactionReceipt deployReceipt;
    protected final TransactionProcessor transactionProcessor;
    protected final Client client;
    public static final String FUNC_DEPLOY = "deploy";
    protected final FunctionEncoder functionEncoder;
    protected final CryptoKeyPair credential;
    protected final CryptoSuite cryptoSuite;
    protected final EventEncoder eventEncoder;
    protected static String LATEST_BLOCK = "latest";

    /**
     * Constructor
     *
     * @param contractBinary       the contract binary code hex string
     * @param contractAddress      the contract address
     * @param client               a Client object
     * @param credential           key pair to use when sign transaction
     * @param transactionProcessor TransactionProcessor object
     */
    protected Contract(
            String contractBinary,
            String contractAddress,
            Client client,
            CryptoKeyPair credential,
            TransactionProcessor transactionProcessor) {
        this.contractBinary = contractBinary;
        this.contractAddress = contractAddress;
        this.client = client;
        this.transactionProcessor = transactionProcessor;
        this.credential = credential;
        this.cryptoSuite = client.getCryptoSuite();
        this.functionEncoder = new FunctionEncoder(client.getCryptoSuite());
        this.eventEncoder = new EventEncoder(client.getCryptoSuite());
    }

    /**
     * Constructor, auto create a TransactionProcessor object
     *
     * @param contractBinary  the contract binary code hex string
     * @param contractAddress the contract address
     * @param client          a Client object to send requests
     * @param credential      key pair to use when sign transaction
     */
    protected Contract(
            String contractBinary,
            String contractAddress,
            Client client,
            CryptoKeyPair credential) {
        this(
                contractBinary,
                contractAddress,
                client,
                credential,
                TransactionProcessorFactory.createTransactionProcessor(client, credential));
    }

    /**
     * Deploy contract
     *
     * @param type
     * @param client             a Client object to send requests
     * @param credential         key pair to use when sign transaction
     * @param transactionManager TransactionProcessor
     * @param binary             the contract binary code hex string
     * @param encodedConstructor
     * @param <T>                a smart contract object extends Contract
     * @return <T> type smart contract
     * @throws ContractException
     */
    protected static <T extends Contract> T deploy(
            Class<T> type,
            Client client,
            CryptoKeyPair credential,
            TransactionProcessor transactionManager,
            String binary,
            String encodedConstructor)
            throws ContractException {
        try {
            Constructor<T> constructor =
                    type.getDeclaredConstructor(String.class, Client.class, CryptoKeyPair.class);
            constructor.setAccessible(true);
            T contract = constructor.newInstance(null, client, credential);
            return create(contract, binary, encodedConstructor);
        } catch (InstantiationException
                | InvocationTargetException
                | NoSuchMethodException
                | IllegalAccessException e) {
            throw new ContractException("deploy contract failed, error info: " + e.getMessage());
        }
    }

    protected static <T extends Contract> T deploy(
            Class<T> type,
            Client client,
            CryptoKeyPair credential,
            String binary,
            String encodedConstructor)
            throws ContractException {
        return deploy(
                type,
                client,
                credential,
                TransactionProcessorFactory.createTransactionProcessor(client, credential),
                binary,
                encodedConstructor);
    }

    private static <T extends Contract> T create(
            T contract, String binary, String encodedConstructor) throws ContractException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            outputStream.write(Hex.decode(binary));
            outputStream.write(Hex.decode(encodedConstructor));
        } catch (IOException e) {
            e.printStackTrace();
        }
        TransactionReceipt transactionReceipt =
                contract.executeTransaction(outputStream.toByteArray(), FUNC_DEPLOY);

        String contractAddress = transactionReceipt.getContractAddress();
        if (contractAddress == null) {
            // parse the receipt
            RetCode retCode = ReceiptParser.parseTransactionReceipt(transactionReceipt);
            throw new ContractException(
                    "Deploy contract failed, error message: " + retCode.getMessage());
        }
        contract.setContractAddress(contractAddress);
        contract.setDeployReceipt(transactionReceipt);
        return contract;
    }

    public String getContractAddress() {
        return this.contractAddress;
    }

    public void setContractAddress(String contractAddress) {
        this.contractAddress = contractAddress;
    }

    public TransactionReceipt getDeployReceipt() {
        return this.deployReceipt;
    }

    public void setDeployReceipt(TransactionReceipt deployReceipt) {
        this.deployReceipt = deployReceipt;
    }

    private List<Type> executeCall(Function function) throws ContractException {

        byte[] encodedFunctionData = this.functionEncoder.encode(function);
        CallRequest callRequest =
                new CallRequest(this.credential.getAddress(), this.contractAddress, encodedFunctionData);
        Call response = this.transactionProcessor.executeCall(callRequest);
        // get value from the response
        String callResult = response.getCallResult().getOutput();
        if (!response.getCallResult().getStatus().equals(0)) {
            ContractException contractException =
                    new ContractException(
                            "execute "
                                    + function.getName()
                                    + " failed for non-zero status "
                                    + response.getCallResult().getStatus(),
                            response.getCallResult());
            logger.warn(
                    "status of executeCall is non-success, status: {}, callResult: {}",
                    response.getCallResult().getStatus(),
                    response.getCallResult().toString());
            throw ReceiptParser.parseExceptionCall(contractException);
        }
        try {
            return FunctionReturnDecoder.decode(callResult, function.getOutputParameters());
        } catch (Exception e) {
            throw new ContractException(
                    "decode callResult failed, error info:" + e.getMessage(),
                    e,
                    response.getCallResult());
        }
    }

    protected <T extends Type> T executeCallWithSingleValueReturn(Function function)
            throws ContractException {
        List<Type> values = this.executeCall(function);
        if (!values.isEmpty()) {
            return (T) values.get(0);
        } else {
            throw new ContractException(
                    "executeCall for function "
                            + function.getName()
                            + " failed for empty returned value from the contract "
                            + this.contractAddress);
        }
    }

    protected <T extends Type, R> R executeCallWithSingleValueReturn(
            Function function, Class<R> returnType) throws ContractException {
        T result = this.executeCallWithSingleValueReturn(function);
        // cast the value into returnType
        Object value = result.getValue();
        if (returnType.isAssignableFrom(value.getClass())) {
            return (R) value;
        } else if (result.getClass().equals(Address.class) && returnType.equals(String.class)) {
            return (R) result.toString(); // cast isn't necessary
        } else {
            throw new ContractException(
                    "Unable convert response "
                            + value
                            + " to expected type "
                            + returnType.getSimpleName());
        }
    }

    protected List<Type> executeCallWithMultipleValueReturn(Function function)
            throws ContractException {
        return this.executeCall(function);
    }

    protected void asyncExecuteTransaction(
            byte[] data, String funName, TransactionCallback callback) {
        this.transactionProcessor.sendTransactionAsync(this.contractAddress, data, this.credential, callback);
    }

    protected void asyncExecuteTransaction(Function function, TransactionCallback callback) {
        this.asyncExecuteTransaction(this.functionEncoder.encode(function), function.getName(), callback);
    }

    protected TransactionReceipt executeTransaction(Function function) {
        return this.executeTransaction(this.functionEncoder.encode(function), function.getName());
    }

    protected TransactionReceipt executeTransaction(byte[] data, String functionName) {
        return this.transactionProcessor.sendTransactionAndGetReceipt(this.contractAddress, data,
                this.credential);
    }

    /**
     * Adds a log field to {@link EventValues}.
     */
    public static class EventValuesWithLog {
        private final EventValues eventValues;
        private final TransactionReceipt.Logs log;

        private EventValuesWithLog(EventValues eventValues, TransactionReceipt.Logs log) {
            this.eventValues = eventValues;
            this.log = log;
        }

        public List<Type> getIndexedValues() {
            return this.eventValues.getIndexedValues();
        }

        public List<Type> getNonIndexedValues() {
            return this.eventValues.getNonIndexedValues();
        }

        public TransactionReceipt.Logs getLog() {
            return this.log;
        }
    }

    protected String createSignedTransaction(Function function) {
        return this.createSignedTransaction(this.contractAddress, this.functionEncoder.encode(function));
    }

    protected String createSignedTransaction(String to, byte[] data) {
        return this.transactionProcessor.createSignedTransaction(to, data, this.credential);
    }

    public static EventValues staticExtractEventParameters(
            EventEncoder eventEncoder, Event event, TransactionReceipt.Logs log) {
        List<String> topics = log.getTopics();
        String encodedEventSignature = eventEncoder.encode(event);
        if (!topics.get(0).equals(encodedEventSignature)) {
            return null;
        }

        List<Type> indexedValues = new ArrayList<>();
        List<Type> nonIndexedValues =
                FunctionReturnDecoder.decode(log.getData(), event.getNonIndexedParameters());

        List<TypeReference<Type>> indexedParameters = event.getIndexedParameters();
        for (int i = 0; i < indexedParameters.size(); i++) {
            Type value =
                    FunctionReturnDecoder.decodeIndexedValue(
                            topics.get(i + 1), indexedParameters.get(i));
            indexedValues.add(value);
        }
        return new EventValues(indexedValues, nonIndexedValues);
    }

    protected EventValues extractEventParameters(Event event, TransactionReceipt.Logs log) {
        return staticExtractEventParameters(this.eventEncoder, event, log);
    }

    protected List<EventValues> extractEventParameters(
            Event event, TransactionReceipt transactionReceipt) {
        return transactionReceipt.getLogs().stream()
                .map(log -> this.extractEventParameters(event, log))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    protected EventValuesWithLog extractEventParametersWithLog(
            Event event, TransactionReceipt.Logs log) {
        final EventValues eventValues = this.extractEventParameters(event, log);
        return (eventValues == null) ? null : new EventValuesWithLog(eventValues, log);
    }

    protected List<EventValuesWithLog> extractEventParametersWithLog(
            Event event, TransactionReceipt transactionReceipt) {
        return transactionReceipt.getLogs().stream()
                .map(log -> this.extractEventParametersWithLog(event, log))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    protected List<EventValuesWithLog> extractEventParametersWithLog(
            Event event, List<TransactionReceipt.Logs> logs) {
        return logs.stream()
                .map(log -> this.extractEventParametersWithLog(event, log))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public static <S extends Type, T> List<T> convertToNative(List<S> arr) {
        List<T> out = new ArrayList<T>();
        for (Iterator<S> it = arr.iterator(); it.hasNext(); ) {
            out.add((T) it.next().getValue());
        }
        return out;
    }

    public TransactionProcessor getTransactionProcessor() {
        return this.transactionProcessor;
    }

    public String getCurrentExternalAccountAddress() {
        return this.credential.getAddress();
    }
}
