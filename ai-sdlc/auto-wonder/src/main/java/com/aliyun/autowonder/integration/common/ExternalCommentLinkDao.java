package com.aliyun.autowonder.integration.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ExternalCommentLinkDao {
    void insert(ExternalCommentLinkDO link);
    ExternalCommentLinkDO findByExternalScope(@Param("tenantId") Long tenantId,
                                              @Param("bindingId") Long bindingId,
                                              @Param("externalWorkitemId") String externalWorkitemId,
                                              @Param("externalCommentId") String externalCommentId);
    int updateSourceMetadata(ExternalCommentLinkDO link);
    ExternalCommentLinkDO findByLocalComment(@Param("tenantId") Long tenantId, @Param("workitemCommentId") Long workitemCommentId);
}
