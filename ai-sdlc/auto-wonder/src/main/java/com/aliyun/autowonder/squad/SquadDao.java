package com.aliyun.autowonder.squad;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Collection;
import java.util.List;

@Mapper
public interface SquadDao {
    void insert(SquadDO squad);
    SquadDO findById(@Param("id") Long id);
    List<SquadDO> listByIds(@Param("ids") Collection<Long> ids);
    List<SquadDO> list(@Param("offset") int offset, @Param("limit") int limit);
    int update(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("name") String name, @Param("description") String description,
            @Param("ownerId") Long ownerId, @Param("debugLogEnabled") Boolean debugLogEnabled,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId,
            @Param("version") Integer version, @Param("modifierId") Long modifierId);

    /** agent 所属（未解散）小队中开启 debug 日志收集的数量；打包冻结判定用。 */
    int countDebugEnabledByAgent(@Param("tenantId") Long tenantId, @Param("agentId") Long agentId);
}
