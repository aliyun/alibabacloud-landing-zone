package com.aliyun.autowonder.memory.store.dto;

import com.aliyun.autowonder.memory.store.MemoryChangeDO;

import java.util.List;

public record MemoryChangePageVO(long storeId, long currentRevision, List<MemoryChangeDO> changes,
                                 boolean hasMore) {
}
