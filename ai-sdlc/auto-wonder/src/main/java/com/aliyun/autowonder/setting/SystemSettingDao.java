package com.aliyun.autowonder.setting;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SystemSettingDao {
    void insert(SystemSettingDO setting);

    SystemSettingDO findByUk(@Param("tenantId") Long tenantId,
                             @Param("settingGroup") String settingGroup,
                             @Param("settingKey") String settingKey);

    List<SystemSettingDO> listByGroup(@Param("tenantId") Long tenantId,
                                      @Param("settingGroup") String settingGroup);

    int update(@Param("id") Long id, @Param("tenantId") Long tenantId,
               @Param("valueJson") String valueJson,
               @Param("isSecret") Integer isSecret,
               @Param("credentialRef") String credentialRef,
               @Param("modifierId") Long modifierId);

    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
