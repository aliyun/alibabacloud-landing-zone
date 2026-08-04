package com.aliyun.autowonder.dispatch;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DispatchRuntimeEventDao {
    void insert(DispatchRuntimeEventDO event);

    List<DispatchRuntimeEventDO> listByWorkitem(@Param("tenantId") Long tenantId,
                                                @Param("workitemId") Long workitemId);

    List<DispatchRuntimeEventDO> listByDispatch(@Param("tenantId") Long tenantId,
                                                @Param("dispatchId") Long dispatchId);

    DispatchRuntimeEventDO findLatestByDispatchAndType(@Param("tenantId") Long tenantId,
                                                       @Param("dispatchId") Long dispatchId,
                                                       @Param("eventType") String eventType);
}
