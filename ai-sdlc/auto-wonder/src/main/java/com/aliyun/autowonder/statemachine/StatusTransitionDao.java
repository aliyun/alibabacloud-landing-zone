package com.aliyun.autowonder.statemachine;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface StatusTransitionDao {
    void insert(StatusTransitionDO t);
    StatusTransitionDO findByTemplateFromTo(@Param("templateId") Long templateId,
            @Param("fromNodeId") Long fromNodeId, @Param("toNodeId") Long toNodeId);
    StatusTransitionDO findById(@Param("id") Long id);
    List<StatusTransitionDO> listByTemplateId(@Param("templateId") Long templateId);
    int update(@Param("id") Long id, @Param("fromNodeId") Long fromNodeId,
               @Param("toNodeId") Long toNodeId, @Param("name") String name);
    int deleteById(@Param("id") Long id);
    int deleteByTemplateId(@Param("templateId") Long templateId);
}
