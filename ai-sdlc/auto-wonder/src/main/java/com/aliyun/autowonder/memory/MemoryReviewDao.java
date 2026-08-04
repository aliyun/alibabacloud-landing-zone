package com.aliyun.autowonder.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface MemoryReviewDao {
    void insert(MemoryReviewDO review);
    List<MemoryReviewDO> listByMemoryId(@Param("memoryId") Long memoryId);
}
