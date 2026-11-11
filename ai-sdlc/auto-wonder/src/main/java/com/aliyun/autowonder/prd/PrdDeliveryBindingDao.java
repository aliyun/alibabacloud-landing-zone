package com.aliyun.autowonder.prd;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Delivery binding persistence: one pinned design version per dispatch. Every statement carries an
 * explicit {@code workspace_id} predicate, as with the other workspace-named PRD tables.
 */
@Mapper
public interface PrdDeliveryBindingDao {

    /** Pin a binding; a duplicate dispatch id is ignored so re-packaging stays idempotent. */
    int insertIgnore(PrdDeliveryBindingDO binding);

    PrdDeliveryBindingDO findByDispatch(@Param("workspaceId") Long workspaceId,
                                        @Param("dispatchId") Long dispatchId);

    /** Bindings for a workitem, newest first — used to list deliveries driven by a design. */
    List<PrdDeliveryBindingDO> listByWorkitem(@Param("workspaceId") Long workspaceId,
                                              @Param("workitemId") Long workitemId,
                                              @Param("limit") int limit);

    /** Bindings pointing at one version, used to warn when a design is superseded mid-delivery. */
    List<PrdDeliveryBindingDO> listByDesignVersion(@Param("workspaceId") Long workspaceId,
                                                   @Param("designVersionId") Long designVersionId);

    int deleteByWorkitem(@Param("workspaceId") Long workspaceId,
                         @Param("workitemId") Long workitemId);
}
