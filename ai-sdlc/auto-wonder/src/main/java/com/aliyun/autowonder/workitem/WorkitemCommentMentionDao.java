package com.aliyun.autowonder.workitem;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WorkitemCommentMentionDao {
    void insert(WorkitemCommentMentionDO mention);

    List<WorkitemCommentMentionDO> listByWorkitem(@Param("tenantId") Long tenantId,
                                                   @Param("workitemId") Long workitemId);
}
