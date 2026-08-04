package com.aliyun.autowonder.executor;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Collection;
import java.util.List;

@Mapper
public interface ExecutorDao {
    List<ExecutorDO> listByIds(@Param("tenantId") Long tenantId, @Param("ids") Collection<Long> ids);
    void insert(ExecutorDO executor);
    ExecutorDO findById(@Param("id") Long id);
    List<ExecutorDO> listAll(@Param("tenantId") Long tenantId);
    List<ExecutorDO> listByAgent(@Param("tenantId") Long tenantId, @Param("agentId") Long agentId);
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId,
                   @Param("modifierId") Long modifierId);
    int updateTokenRef(@Param("id") Long id, @Param("tokenRef") String tokenRef);
    int updateLastConnectIp(@Param("id") Long id, @Param("tenantId") Long tenantId,
                            @Param("lastConnectIp") String lastConnectIp, @Param("modifierId") Long modifierId);
    int updateLastHeartbeat(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
