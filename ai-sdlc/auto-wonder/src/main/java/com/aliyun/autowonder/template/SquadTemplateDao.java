package com.aliyun.autowonder.template;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SquadTemplateDao {
    void insert(SquadTemplateDO template);
    SquadTemplateDO findById(@Param("id") Long id);
    List<SquadTemplateDO> listActive(@Param("tenantId") Long tenantId);
}
