package com.aliyun.autowonder.dispatch;

import org.apache.ibatis.annotations.*;

@Mapper
public interface DispatchModelSelectionDao {
    @Select("SELECT selection_json FROM dispatch_model_selection WHERE tenant_id=#{tenantId} AND dispatch_id=#{dispatchId}")
    String find(@Param("tenantId") long tenantId, @Param("dispatchId") long dispatchId);

    @Insert("INSERT IGNORE INTO dispatch_model_selection (tenant_id, dispatch_id, selection_json) VALUES (#{tenantId}, #{dispatchId}, #{json})")
    int insert(@Param("tenantId") long tenantId, @Param("dispatchId") long dispatchId, @Param("json") String json);
}
