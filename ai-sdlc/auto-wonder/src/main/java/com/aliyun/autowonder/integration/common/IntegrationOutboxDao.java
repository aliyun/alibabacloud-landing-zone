package com.aliyun.autowonder.integration.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface IntegrationOutboxDao {
    void insert(IntegrationOutboxDO outbox);
    IntegrationOutboxDO findById(@Param("id") Long id);
    IntegrationOutboxDO findByOperation(@Param("tenantId") Long tenantId,
                                        @Param("provider") String provider,
                                        @Param("bindingId") Long bindingId,
                                        @Param("operationKey") String operationKey);
    List<IntegrationOutboxDO> listPending(@Param("provider") String provider, @Param("limit") int limit);
    List<IntegrationOutboxDO> listPendingAny(@Param("limit") int limit);
    List<IntegrationOutboxDO> listPendingExcludingProvider(@Param("provider") String provider,
                                                           @Param("limit") int limit);
    List<IntegrationOutboxDO> listRecoveryCandidates(@Param("before") java.util.Date before,
                                                      @Param("limit") int limit);
    int markSending(@Param("id") Long id, @Param("expectedLockVersion") long expectedLockVersion);
    int takeoverForRecovery(@Param("id") Long id,
                            @Param("expectedLockVersion") long expectedLockVersion,
                            @Param("before") java.util.Date before);
    int markSucceeded(@Param("id") Long id,
                      @Param("lockVersion") long lockVersion);
    int markUnknown(@Param("id") Long id,
                    @Param("lockVersion") long lockVersion,
                    @Param("lastError") String lastError);
    int markFailed(@Param("id") Long id,
                   @Param("lockVersion") long lockVersion,
                   @Param("retryable") boolean retryable,
                   @Param("lastError") String lastError);
    int requeueAfterNotFound(@Param("id") Long id, @Param("lockVersion") long lockVersion);
    int manualRetry(@Param("id") Long id,
                    @Param("tenantId") Long tenantId,
                    @Param("expectedLockVersion") long expectedLockVersion);
    int manualConfirmSucceeded(@Param("id") Long id,
                               @Param("tenantId") Long tenantId,
                               @Param("expectedLockVersion") long expectedLockVersion);
}
