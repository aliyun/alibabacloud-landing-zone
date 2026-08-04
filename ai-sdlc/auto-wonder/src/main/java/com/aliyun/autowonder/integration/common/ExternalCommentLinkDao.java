package com.aliyun.autowonder.integration.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ExternalCommentLinkDao {
    void insert(ExternalCommentLinkDO link);
    ExternalCommentLinkDO findByExternal(@Param("tenantId") Long tenantId, @Param("provider") String provider,
                                         @Param("externalCommentId") String externalCommentId);
    ExternalCommentLinkDO findByLocalComment(@Param("tenantId") Long tenantId, @Param("workitemCommentId") Long workitemCommentId);
}
