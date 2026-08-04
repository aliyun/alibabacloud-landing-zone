package com.aliyun.autowonder.agent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Collection;
import java.util.List;

@Mapper
public interface AgentDao {
    List<AgentDO> listByIds(@Param("tenantId") Long tenantId, @Param("ids") Collection<Long> ids);
    List<AgentDO> listByTenant(@Param("tenantId") Long tenantId);
    void insert(AgentDO agent);
    AgentDO findById(@Param("id") Long id);
    List<AgentDO> findByExactName(@Param("tenantId") Long tenantId, @Param("name") String name);
    List<AgentDO> list(@Param("status") String status, @Param("offset") int offset, @Param("limit") int limit);
    int updateStatus(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("status") String status, @Param("onlineVersionId") Long onlineVersionId,
            @Param("editingVersionId") Long editingVersionId,
            @Param("latestVersionNo") Integer latestVersionNo,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int updateName(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("name") String name, @Param("version") Integer version,
            @Param("modifierId") Long modifierId);
    List<AgentDO> findOnlineByRoleCode(@Param("tenantId") Long tenantId,
            @Param("roleCode") String roleCode);
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int countByStatus(@Param("tenantId") Long tenantId, @Param("status") String status);
}
