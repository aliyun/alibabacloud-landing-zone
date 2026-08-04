package com.aliyun.autowonder.integration.aone;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AoneRateBucketDao {

    /**
     * Atomically refills the token bucket for {@code clientKey} based on elapsed time and, if a
     * whole token is available, consumes one. Returns the affected row count: {@code 1} when a
     * token was consumed, {@code 0} when the bucket is empty (or the row does not exist).
     */
    int tryAcquire(@Param("clientKey") String clientKey);
}
