package com.aliyun.autowonder.statemachine;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface StatusTemplateDao {
    void insert(StatusTemplateDO t);
    StatusTemplateDO findDefaultByType(@Param("workType") String workType);
    StatusTemplateDO findById(@Param("id") Long id);
    List<StatusTemplateDO> listByWorkType(@Param("tenantId") Long tenantId, @Param("workType") String workType);
    int update(@Param("id") Long id, @Param("tenantId") Long tenantId,
               @Param("name") String name, @Param("isDefault") Integer isDefault,
               @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("version") Integer version);
    void clearDefault(@Param("tenantId") Long tenantId, @Param("workType") String workType);
}
