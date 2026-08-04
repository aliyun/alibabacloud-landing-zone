package com.aliyun.autowonder.squad;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SquadMemberDao {
    void insert(SquadMemberDO member);
    List<SquadMemberDO> listBySquad(@Param("squadId") Long squadId);
    int countBySquad(@Param("squadId") Long squadId);
    List<SquadMemberDO> listByAgent(@Param("agentId") Long agentId);
    SquadMemberDO findBySquadAndAgent(@Param("squadId") Long squadId, @Param("agentId") Long agentId);
    int deleteBySquadAndAgent(@Param("squadId") Long squadId, @Param("agentId") Long agentId, @Param("tenantId") Long tenantId);
    int deleteBySquad(@Param("squadId") Long squadId, @Param("tenantId") Long tenantId);
}
