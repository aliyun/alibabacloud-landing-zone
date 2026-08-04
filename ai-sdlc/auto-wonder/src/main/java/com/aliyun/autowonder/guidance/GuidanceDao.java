package com.aliyun.autowonder.guidance;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface GuidanceDao {
    void insert(GuidanceDO guidance);
    GuidanceDO findById(@Param("id") Long id);
    List<GuidanceDO> listByWorkitem(@Param("tenantId") Long tenantId,
            @Param("workitemId") Long workitemId);
    List<GuidanceDO> listQueuedForDispatch(@Param("tenantId") Long tenantId,
            @Param("dispatchId") Long dispatchId);
    List<GuidanceDO> listDeliveredForExecutor(@Param("tenantId") Long tenantId,
            @Param("executorId") Long executorId);
    int bindDispatch(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("dispatchId") Long dispatchId, @Param("executorId") Long executorId);
    int bindPendingDispatch(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("dispatchId") Long dispatchId);
    int updateStatus(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("status") String status, @Param("error") String error);
    int acknowledge(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("executorId") Long executorId, @Param("status") String status,
            @Param("error") String error);
    int bindReplyComment(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("replyCommentId") Long replyCommentId);
    int requeueDeliveredForDispatch(@Param("tenantId") Long tenantId,
            @Param("dispatchId") Long dispatchId);
    int requeueForExecutorFailover(@Param("tenantId") Long tenantId,
            @Param("dispatchId") Long dispatchId);
    int failForDispatch(@Param("tenantId") Long tenantId,
            @Param("dispatchId") Long dispatchId, @Param("error") String error);
}
