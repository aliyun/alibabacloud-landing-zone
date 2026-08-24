package com.aliyun.autowonder.integration.receipt;

public class ExternalOperationReceiptConflictException extends RuntimeException {
    public ExternalOperationReceiptConflictException(String operationKey) {
        super("external operation key was reused with a different payload: " + operationKey);
    }
}
