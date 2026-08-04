package com.aliyun.autowonder.sdlc;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SdlcStepDao {
    void insert(SdlcStepDO step);
    SdlcStepDO findById(@Param("id") Long id);
    List<SdlcStepDO> listBySdlc(@Param("sdlcId") Long sdlcId);
    int update(@Param("id") Long id, @Param("tenantId") Long tenantId,
               @Param("name") String name,
               @Param("kind") String kind,
               @Param("instructionMd") String instructionMd,
               @Param("checklistJson") String checklistJson,
               @Param("gatePolicyJson") String gatePolicyJson,
               @Param("required") Boolean required,
               @Param("timeoutSeconds") Integer timeoutSeconds,
               @Param("retryBudget") Integer retryBudget,
               @Param("code") String code,
               @Param("handlerType") String handlerType,
               @Param("handlerRoleRef") String handlerRoleRef,
               @Param("statusOnEnterCode") String statusOnEnterCode,
               @Param("onSuccess") String onSuccess, @Param("onFail") String onFail,
               @Param("modifierId") Long modifierId);
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId,
                   @Param("modifierId") Long modifierId);
    int deleteAllBySdlc(@Param("sdlcId") Long sdlcId, @Param("tenantId") Long tenantId);
    int updateOrder(@Param("id") Long id, @Param("tenantId") Long tenantId,
                    @Param("stepOrder") Integer stepOrder, @Param("modifierId") Long modifierId);
}
