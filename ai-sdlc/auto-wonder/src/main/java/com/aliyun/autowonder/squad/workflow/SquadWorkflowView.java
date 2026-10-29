package com.aliyun.autowonder.squad.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Date;

/** Read model returned by the three workflow endpoints. */
public record SquadWorkflowView(
        String status,
        String inputHash,
        String analysisVersion,
        Long generationId,
        String attemptId,
        Date generatedAt,
        ErrorInfo error,
        JsonNode graph) {

    public static final String EMPTY = "EMPTY";
    public static final String MISSING = "MISSING";
    public static final String GENERATING = "GENERATING";
    public static final String REPAIRING = "REPAIRING";
    public static final String READY = "READY";
    public static final String FAILED = "FAILED";

    public record ErrorInfo(String code, String message, boolean retryable) {
    }
}
