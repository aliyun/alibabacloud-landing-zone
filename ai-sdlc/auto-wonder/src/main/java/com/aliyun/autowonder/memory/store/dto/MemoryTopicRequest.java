package com.aliyun.autowonder.memory.store.dto;

public record MemoryTopicRequest(String path, String type, String title, String description,
                                 String contentMd, Integer expectedVersion, String idempotencyKey) {}
