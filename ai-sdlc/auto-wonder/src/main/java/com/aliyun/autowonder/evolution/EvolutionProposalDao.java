package com.aliyun.autowonder.evolution;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EvolutionProposalDao {
    void insert(EvolutionProposalDO proposal);

    EvolutionProposalDO findById(@Param("id") Long id);

	EvolutionProposalDO findActiveSkillTrial(@Param("tenantId") Long tenantId,
											 @Param("taskPatternKey") String taskPatternKey);

    int markValidated(@Param("id") Long id, @Param("tenantId") Long tenantId,
                      @Param("lifecycleJson") String lifecycleJson,
                      @Param("version") Integer version, @Param("modifierId") Long modifierId);

    int markReplay(@Param("id") Long id, @Param("tenantId") Long tenantId,
                   @Param("status") String status, @Param("lifecycleJson") String lifecycleJson,
                   @Param("version") Integer version, @Param("modifierId") Long modifierId);

    int markTrial(@Param("id") Long id, @Param("tenantId") Long tenantId,
                  @Param("lifecycleJson") String lifecycleJson,
                  @Param("version") Integer version, @Param("modifierId") Long modifierId);

    int markTrialDecision(@Param("id") Long id, @Param("tenantId") Long tenantId,
                          @Param("status") String status, @Param("lifecycleJson") String lifecycleJson,
                          @Param("version") Integer version, @Param("modifierId") Long modifierId);

    int markApproved(@Param("id") Long id, @Param("tenantId") Long tenantId,
                     @Param("version") Integer version, @Param("modifierId") Long modifierId);

    int markRejected(@Param("id") Long id, @Param("tenantId") Long tenantId,
                     @Param("lifecycleJson") String lifecycleJson,
                     @Param("version") Integer version, @Param("modifierId") Long modifierId);

    int markGate(@Param("id") Long id, @Param("tenantId") Long tenantId,
                 @Param("lifecycleJson") String lifecycleJson,
                 @Param("version") Integer version, @Param("modifierId") Long modifierId);

    int markReleased(@Param("id") Long id, @Param("tenantId") Long tenantId,
                     @Param("lifecycleJson") String lifecycleJson,
                     @Param("version") Integer version, @Param("modifierId") Long modifierId);

    int markRolledBack(@Param("id") Long id, @Param("tenantId") Long tenantId,
                       @Param("lifecycleJson") String lifecycleJson,
                       @Param("version") Integer version, @Param("modifierId") Long modifierId);

    List<EvolutionProposalDO> listRecent(@Param("tenantId") Long tenantId,
                                         @Param("limit") int limit);
}
