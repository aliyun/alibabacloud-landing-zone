package com.aliyun.autowonder.environment;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EnvironmentVariableDao {
    int insert(EnvironmentVariableDO variable);

    List<EnvironmentVariableDO> listActive(@Param("tenantId") Long tenantId);

    EnvironmentVariableDO findActiveById(@Param("tenantId") Long tenantId,
                                          @Param("id") Long id);

    /** Shared lock protocol for deletion and Task 2 mount validation. */
    EnvironmentVariableDO findActiveByIdForUpdate(@Param("tenantId") Long tenantId,
                                                   @Param("id") Long id);

    EnvironmentVariableDO findActiveByNameIgnoreCase(@Param("tenantId") Long tenantId,
                                                      @Param("name") String name);

    int updateMetadata(@Param("tenantId") Long tenantId,
                       @Param("id") Long id,
                       @Param("name") String name,
                       @Param("description") String description,
                       @Param("modifierId") Long modifierId,
                       @Param("version") Integer version);

    int updateWithValue(@Param("tenantId") Long tenantId,
                        @Param("id") Long id,
                        @Param("name") String name,
                        @Param("description") String description,
                        @Param("credentialRef") String credentialRef,
                        @Param("modifierId") Long modifierId,
                        @Param("version") Integer version);

    int softDelete(@Param("tenantId") Long tenantId,
                   @Param("id") Long id,
                   @Param("version") Integer version,
                   @Param("modifierId") Long modifierId);

    /**
     * Deletion-protection boundary shared with the agent binding slice. Implementations return only
     * references from an agent's current online version or current editing draft.
     */
    List<EnvironmentVariableReference> listActiveReferences(
            @Param("tenantId") Long tenantId,
            @Param("environmentVariableId") Long environmentVariableId);
}
