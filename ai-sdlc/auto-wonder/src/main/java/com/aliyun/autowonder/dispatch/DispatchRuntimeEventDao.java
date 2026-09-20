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

    List<DispatchRuntimeEventDO> listByDispatchInArrivalOrder(@Param("tenantId") Long tenantId,
                                                               @Param("dispatchId") Long dispatchId);

    /** Rows newer than a client cursor, so a backfill read does not reproject the whole history. */
    List<DispatchRuntimeEventDO> listByDispatchAfterSeq(@Param("tenantId") Long tenantId,
                                                        @Param("dispatchId") Long dispatchId,
                                                        @Param("afterSeq") long afterSeq);

    DispatchRuntimeEventDO findLatestByDispatchAndType(@Param("tenantId") Long tenantId,
                                                       @Param("dispatchId") Long dispatchId,
                                                       @Param("eventType") String eventType);
}
