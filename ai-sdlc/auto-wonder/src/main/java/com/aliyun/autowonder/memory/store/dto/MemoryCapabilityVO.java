package com.aliyun.autowonder.memory.store.dto;

import java.util.Map;

public record MemoryCapabilityVO(String snapshotPath, String changesPath, String mutationPath,
                                 String recallPath, Map<Long, Long> startingRevisions) {
}
