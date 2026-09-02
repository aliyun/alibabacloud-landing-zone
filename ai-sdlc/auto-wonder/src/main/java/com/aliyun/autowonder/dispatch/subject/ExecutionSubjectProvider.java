package com.aliyun.autowonder.dispatch.subject;

import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.taskpackage.PackageContext;

public interface ExecutionSubjectProvider {
    ExecutionSourceType type();

    /** Loads the source by a workspace-qualified identity and fails when unavailable or foreign. */
    ExecutionSubject load(long workspaceId, long sourceId);

    /**
     * Builds the immutable package view. Workitems receive the already selected online version;
     * scheduled runs additionally verify it against their frozen snapshot.
     */
    PackageContext assemble(DispatchDO dispatch, ExecutionSubject subject, AgentVersionDO version);
}
