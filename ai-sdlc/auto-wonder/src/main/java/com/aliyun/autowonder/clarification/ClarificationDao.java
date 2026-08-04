package com.aliyun.autowonder.clarification;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ClarificationDao {
    ClarificationDO findByWorkitem(@Param("workitemId") Long workitemId);
    void insert(ClarificationDO c);
    int update(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("contentMd") String contentMd);
}
