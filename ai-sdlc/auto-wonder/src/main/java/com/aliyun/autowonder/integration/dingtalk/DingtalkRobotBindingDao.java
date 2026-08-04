package com.aliyun.autowonder.integration.dingtalk;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface DingtalkRobotBindingDao {
    int insert(DingtalkRobotBindingDO row);

    DingtalkRobotBindingDO findById(@Param("tenantId") Long tenantId, @Param("id") Long id);

    DingtalkRobotBindingDO findByRobotCode(@Param("tenantId") Long tenantId,
            @Param("robotCode") String robotCode);

    DingtalkRobotBindingDO findByRobotCodeGlobal(@Param("robotCode") String robotCode);

    List<DingtalkRobotBindingDO> listByTenant(@Param("tenantId") Long tenantId);

    List<DingtalkRobotBindingDO> listEnabledByTransportMode(@Param("transportMode") String transportMode);

    int update(DingtalkRobotBindingDO row);

    int updateHealth(@Param("tenantId") Long tenantId, @Param("id") Long id,
            @Param("lastSuccessAt") Date lastSuccessAt, @Param("lastError") String lastError);

    int deleteById(@Param("tenantId") Long tenantId, @Param("id") Long id);
}
