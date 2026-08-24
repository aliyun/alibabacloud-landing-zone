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
    List<WorkitemCommentDO> listByWorkitem(@Param("workitemId") Long workitemId);
}
