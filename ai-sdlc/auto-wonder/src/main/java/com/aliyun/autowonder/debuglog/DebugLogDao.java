package com.aliyun.autowonder.debuglog;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface DebugLogDao {

    void insert(DebugLogDO row);

    /** uk_dispatch 保证至多一行；签发幂等与 insert-or-update 的读侧。 */
    DebugLogDO findByDispatchId(@Param("dispatchId") Long dispatchId);

    /** 重复签发刷新申请快照并复位 PENDING；已 UPLOADED 的行不受影响。 */
    int updateOnIssue(@Param("id") Long id, @Param("runNo") Integer runNo,
                      @Param("objectKey") String objectKey, @Param("sizeBytes") Long sizeBytes,
                      @Param("sha256") String sha256, @Param("truncated") Boolean truncated,
                      @Param("dispatchStatus") String dispatchStatus);

    /**
     * TASK_RESULT 报告 / 中转登记收尾；null 字段保留既有值（error_message 除外，总是覆写——
     * UPLOADED 行可携带收尾注记，协议契约）。UPLOADED 为终态单调守卫：命中 UPLOADED 行时
     * rows==0，表示重复投递已收敛，调用侧按非错误处理（FAILED→UPLOADED 补传仍允许）。
     */
    int updateOnResult(@Param("id") Long id, @Param("status") String status,
                       @Param("uploadChannel") String uploadChannel,
                       @Param("sizeBytes") Long sizeBytes, @Param("sha256") String sha256,
                       @Param("truncated") Boolean truncated,
                       @Param("dispatchStatus") String dispatchStatus,
                       @Param("errorMessage") String errorMessage);

    List<DebugLogDO> listForQuery(@Param("tenantId") Long tenantId,
                                  @Param("sourceType") String sourceType,
                                  @Param("sourceId") Long sourceId,
                                  @Param("agentId") Long agentId,
                                  @Param("since") Date since,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);

    /** 对账扫描：PENDING 且 gmt_modified 早于 cutoff。 */
    List<DebugLogDO> listPendingOlderThan(@Param("beforeEpochMillis") long beforeEpochMillis,
                                          @Param("limit") int limit);

    /** 对账收敛，仅允许改动仍处 PENDING 的行。 */
    int markReconciled(@Param("id") Long id, @Param("status") String status,
                       @Param("errorMessage") String errorMessage);
}
