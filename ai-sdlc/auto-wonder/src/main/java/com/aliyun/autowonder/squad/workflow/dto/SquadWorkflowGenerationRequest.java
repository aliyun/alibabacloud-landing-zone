package com.aliyun.autowonder.squad.workflow.dto;

/** Trigger body. The hash is optional so a fresh page can request generation without a prior GET. */
public record SquadWorkflowGenerationRequest(String expectedInputHash, Boolean retry) {

    public boolean retryRequested() {
        return Boolean.TRUE.equals(retry);
    }
}
