package com.aliyun.autowonder.sdlc;

import com.aliyun.autowonder.sdlc.dto.SdlcStepCount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Collection;
import java.util.List;

@Mapper
public interface SdlcStepDao {
    void insert(SdlcStepDO step);
    SdlcStepDO findById(@Param("id") Long id);
    List<SdlcStepDO> listBySdlc(@Param("sdlcId") Long sdlcId);
    // 一次聚合出多个 SDLC 的未删除步骤数，供列表页填充 stepCount，避免逐个 count 的 N+1。
    List<SdlcStepCount> countBySdlcIds(@Param("sdlcIds") Collection<Long> sdlcIds);
    // 整行覆盖写回：调用方必须先 findById 读出原行、按请求携带情况覆盖后再传入，
    // 否则未赋值的字段会落成 NULL。
    int update(SdlcStepDO step);
    int softDelete(@Param("id") Long id, @Param("tenantId") Long tenantId,
                   @Param("modifierId") Long modifierId);
    int deleteAllBySdlc(@Param("sdlcId") Long sdlcId, @Param("tenantId") Long tenantId);
    int updateOrder(@Param("id") Long id, @Param("tenantId") Long tenantId,
                    @Param("stepOrder") Integer stepOrder, @Param("modifierId") Long modifierId);
}
