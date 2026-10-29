package com.aliyun.autowonder.dispatch;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DispatchRuntimeEventDao {
    void insert(DispatchRuntimeEventDO event);

    List<DispatchRuntimeEventDO> listByWorkitem(@Param("tenantId") Long tenantId,
                                                @Param("workitemId") Long workitemId);

    /** All progress events in arrival order; only workflow plans need detail_json. */
    List<DispatchRuntimeEventDO> listForProgress(@Param("tenantId") Long tenantId,
                                                @Param("workitemId") Long workitemId);

    List<DispatchRuntimeEventDO> listByDispatch(@Param("tenantId") Long tenantId,
                                                @Param("dispatchId") Long dispatchId);

    List<DispatchRuntimeEventDO> listByDispatchInArrivalOrder(@Param("tenantId") Long tenantId,
                                                               @Param("dispatchId") Long dispatchId);

    /** Rows arrived after a client cursor, so a backfill read does not reproject the whole history. */
    List<DispatchRuntimeEventDO> listByDispatchAfterId(@Param("tenantId") Long tenantId,
                                                       @Param("dispatchId") Long dispatchId,
                                                       @Param("afterId") long afterId);

    DispatchRuntimeEventDO findLatestByDispatchAndType(@Param("tenantId") Long tenantId,
                                                       @Param("dispatchId") Long dispatchId,
                                                       @Param("eventType") String eventType);
}
