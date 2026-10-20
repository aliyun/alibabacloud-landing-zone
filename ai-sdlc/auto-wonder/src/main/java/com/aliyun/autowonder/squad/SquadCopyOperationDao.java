package com.aliyun.autowonder.squad;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SquadCopyOperationDao {

    void insert(SquadCopyOperationDO operation);

    SquadCopyOperationDO find(@Param("sourceWorkspaceId") Long sourceWorkspaceId,
                              @Param("userId") Long userId,
                              @Param("requestId") String requestId,
                              @Param("targetWorkspaceId") Long targetWorkspaceId);

    List<SquadCopyOperationDO> listByRequest(@Param("sourceWorkspaceId") Long sourceWorkspaceId,
                                             @Param("userId") Long userId,
                                             @Param("requestId") String requestId);

    int markSuccess(@Param("id") Long id, @Param("targetSquadId") Long targetSquadId);

    int markFailed(@Param("id") Long id,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("resourceCleanupJson") String resourceCleanupJson);
}
