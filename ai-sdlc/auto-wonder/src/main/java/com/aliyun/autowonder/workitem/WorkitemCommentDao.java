package com.aliyun.autowonder.workitem;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface WorkitemCommentDao {
    void insert(WorkitemCommentDO c);
    int updateExternalContent(@Param("tenantId") Long tenantId,
                              @Param("id") Long id,
                              @Param("authorRef") Long authorRef,
                              @Param("contentMd") String contentMd);
    WorkitemCommentDO findById(@Param("tenantId") Long tenantId, @Param("id") Long id);
    List<WorkitemCommentDO> listByWorkitem(@Param("tenantId") Long tenantId,
                                           @Param("workitemId") Long workitemId);
    /** Compatibility shim for tests/old binary callers; never performs an unscoped read. */
    @Deprecated
    default List<WorkitemCommentDO> listByWorkitem(Long workitemId) {
        throw new UnsupportedOperationException("workspace-scoped comment owner is required");
    }
    WorkitemCommentDO findBySourceAndId(@Param("tenantId") Long tenantId,
                                        @Param("sourceType") String sourceType,
                                        @Param("sourceId") Long sourceId,
                                        @Param("id") Long id);
    List<WorkitemCommentDO> listBySource(@Param("tenantId") Long tenantId,
                                         @Param("sourceType") String sourceType,
                                         @Param("sourceId") Long sourceId);
    /**
     * 本人在给定工单下最近一次评论的时间，按工单分组（工单 #55705「我评论过的」列表回填）。
     * 只统计平台真人成功提交的工单评论；数字人代发与外部同步评论不计入。
     */
    List<WorkitemCommentDO> listMyLastCommentTimes(@Param("tenantId") Long tenantId,
                                                   @Param("workitemIds") Collection<Long> workitemIds,
                                                   @Param("authorRef") Long authorRef);
}
