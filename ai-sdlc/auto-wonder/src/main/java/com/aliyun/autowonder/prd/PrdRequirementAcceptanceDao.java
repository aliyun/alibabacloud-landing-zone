package com.aliyun.autowonder.prd;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Acceptance persistence. As with the other workspace-named PRD tables, every statement carries an
 * explicit {@code workspace_id} predicate; these tables are deliberately not registered with
 * {@code TenantTables}, whose rewriter hardcodes the {@code tenant_id} column name.
 */
@Mapper
public interface PrdRequirementAcceptanceDao {

    /** Seed a requirement row; a duplicate (workspace, dispatch, key) is ignored so seeding is idempotent. */
    int insertIgnore(PrdRequirementAcceptanceDO acceptance);

    List<PrdRequirementAcceptanceDO> listByDispatch(@Param("workspaceId") Long workspaceId,
                                                    @Param("dispatchId") Long dispatchId);

    PrdRequirementAcceptanceDO findByDispatchAndKey(@Param("workspaceId") Long workspaceId,
                                                    @Param("dispatchId") Long dispatchId,
                                                    @Param("requirementKey") String requirementKey);

    /** Write an executor report onto a seeded row. Returns 0 when the row is gone or already changed. */
    int updateReport(PrdRequirementAcceptanceDO acceptance);

    /** Deliveries of one workitem, newest first, for the workitem's acceptance overview. */
    List<PrdRequirementAcceptanceDO> listByWorkitem(@Param("workspaceId") Long workspaceId,
                                                    @Param("workitemId") Long workitemId);

    int deleteByWorkitem(@Param("workspaceId") Long workspaceId,
                         @Param("workitemId") Long workitemId);
}
