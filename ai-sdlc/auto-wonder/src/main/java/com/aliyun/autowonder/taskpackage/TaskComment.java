package com.aliyun.autowonder.taskpackage;

/** Immutable comment snapshot; IDs are scoped to the enclosing task package. */
public record TaskComment(long id, String authorType, Long authorRef, String contentMd) {
}
