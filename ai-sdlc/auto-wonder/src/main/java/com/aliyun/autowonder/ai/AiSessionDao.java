package com.aliyun.autowonder.ai;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AiSessionDao {
    void insert(AiSessionDO session);

    AiSessionDO findById(@Param("id") Long id);

    List<AiSessionDO> listByTenantAndScene(@Param("tenantId") Long tenantId,
            @Param("scene") String scene, @Param("bizRefId") Long bizRefId);

    int updateStatus(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("oldStatus") String oldStatus, @Param("newStatus") String newStatus,
            @Param("version") Integer version);

    int updateRunning(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("nodeId") String nodeId, @Param("cliSessionRef") String cliSessionRef,
            @Param("version") Integer version);

    int updateResult(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("resultJson") String resultJson, @Param("newStatus") String newStatus,
            @Param("version") Integer version);

    int updateCliSessionRef(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("cliSessionRef") String cliSessionRef, @Param("version") Integer version);

    int updateFailed(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("error") String error, @Param("version") Integer version);

    List<AiSessionDO> listStuck(@Param("statuses") List<String> statuses,
            @Param("beforeMs") long beforeMs, @Param("limit") int limit);
}
