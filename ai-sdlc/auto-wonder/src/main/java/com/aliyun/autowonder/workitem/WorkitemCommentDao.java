package com.aliyun.autowonder.workitem;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
}
