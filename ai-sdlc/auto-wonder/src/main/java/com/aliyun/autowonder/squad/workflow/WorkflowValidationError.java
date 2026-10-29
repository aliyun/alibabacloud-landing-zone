package com.aliyun.autowonder.squad.workflow;

/** One bounded, machine-readable validation error. {@code path} is a JSON Pointer. */
public record WorkflowValidationError(String code, String path, String message, String expected) {
}
