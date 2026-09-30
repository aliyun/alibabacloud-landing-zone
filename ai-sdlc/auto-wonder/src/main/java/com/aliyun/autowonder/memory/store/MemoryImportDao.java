package com.aliyun.autowonder.memory.store;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Date;

@Mapper
public interface MemoryImportDao {
    void upsertSource(MemoryImportSourceDO source);
    MemoryImportSourceDO findSource(@Param("tenantId") Long tenantId, @Param("agentId") Long agentId,
                                    @Param("providerFamily") String providerFamily,
                                    @Param("logicalPath") String logicalPath,
                                    @Param("installationFingerprint") String installationFingerprint);
    MemoryImportSourceDO findSourceById(@Param("tenantId") Long tenantId, @Param("sourceId") Long sourceId);
    void insertSnapshot(MemoryImportSnapshotDO snapshot);
    int deleteSnapshotsBeforeLatestTwo(@Param("tenantId") Long tenantId,
                                       @Param("sourceId") Long sourceId);
    List<MemoryImportSnapshotDO> listLatestSnapshots(@Param("tenantId") Long tenantId,
                                                     @Param("sourceId") Long sourceId);
    MemoryImportSnapshotDO findSnapshotByHash(@Param("tenantId") Long tenantId,
                                               @Param("sourceId") Long sourceId,
                                               @Param("sanitizedContentSha256") String hash);
    void insertReceipt(MemoryImportReceiptDO receipt);
    void upsertReceipt(MemoryImportReceiptDO receipt);
    int markSourceImported(@Param("tenantId") Long tenantId, @Param("sourceId") Long sourceId,
                           @Param("sanitizedContentSha256") String hash);
    int markSnapshotStatus(@Param("tenantId") Long tenantId, @Param("snapshotId") Long snapshotId,
                           @Param("status") String status);
    int claimSnapshot(@Param("tenantId") Long tenantId, @Param("snapshotId") Long snapshotId,
                      @Param("leaseId") String leaseId, @Param("now") Date now,
                      @Param("leaseUntil") Date leaseUntil);
    int renewSnapshot(@Param("tenantId") Long tenantId, @Param("snapshotId") Long snapshotId,
                      @Param("leaseId") String leaseId, @Param("now") Date now,
                      @Param("leaseUntil") Date leaseUntil);
    int releaseSnapshot(@Param("tenantId") Long tenantId, @Param("snapshotId") Long snapshotId,
                        @Param("leaseId") String leaseId);
    int completeSnapshot(@Param("tenantId") Long tenantId, @Param("snapshotId") Long snapshotId,
                         @Param("leaseId") String leaseId, @Param("now") Date now);
    MemoryImportReceiptDO findSuccessfulReceiptByHash(@Param("tenantId") Long tenantId,
                                                       @Param("agentId") Long agentId,
                                                       @Param("sanitizedContentSha256") String hash);
    List<MemoryImportSourceDO> listSources(@Param("tenantId") Long tenantId);
    List<MemoryImportReceiptDO> listReceipts(@Param("tenantId") Long tenantId,
                                             @Param("sourceId") Long sourceId,
                                             @Param("limit") int limit);
    int updateSourceStatus(@Param("tenantId") Long tenantId, @Param("sourceId") Long sourceId,
                           @Param("status") String status, @Param("version") int version);
    int markStaleBefore(@Param("cutoff") Date cutoff);
}
