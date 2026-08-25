package com.aliyun.autowonder.integration.receipt;

import com.aliyun.autowonder.integration.common.IntegrationOutboxDO;

public interface ExternalOperationReadbackHandler {

    String connector();

    boolean supports(String eventType);

    ReadbackResult readback(IntegrationOutboxDO receipt);

    enum Outcome {
        FOUND,
        DEFINITELY_NOT_FOUND,
        UNAVAILABLE
    }

    record ReadbackResult(Outcome outcome, String detail) {
        public static ReadbackResult found() {
            return new ReadbackResult(Outcome.FOUND, null);
        }

        public static ReadbackResult notFound() {
            return new ReadbackResult(Outcome.DEFINITELY_NOT_FOUND, null);
        }

        public static ReadbackResult unavailable(String detail) {
            return new ReadbackResult(Outcome.UNAVAILABLE, detail);
        }
    }
}
