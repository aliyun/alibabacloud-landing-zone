package com.aliyun.autowonder.statemachine;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Collection;
import java.util.List;

@Mapper
public interface StatusNodeDao {
    void insert(StatusNodeDO n);
    StatusNodeDO findInitNode(@Param("templateId") Long templateId);
    StatusNodeDO findById(@Param("id") Long id);
    List<StatusNodeDO> listByIds(@Param("ids") Collection<Long> ids);
    StatusNodeDO findByTemplateAndCode(@Param("templateId") Long templateId, @Param("code") String code);
    List<StatusNodeDO> listByTemplateId(@Param("templateId") Long templateId);
    int update(@Param("id") Long id, @Param("code") String code,
               @Param("name") String name, @Param("category") String category, @Param("sort") Integer sort);
    int deleteById(@Param("id") Long id);
    int countWorkitemsUsingNode(@Param("nodeId") Long nodeId);
}
