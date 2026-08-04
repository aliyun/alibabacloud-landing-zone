package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.taskpackage.TaskPackageResult;

/**
 * Seam between the dispatch engine (B2a) and the transport (B2b WebSocket).
 * Implementations push a built task package to the dispatch's selected executor.
 */
public interface DispatchTransport {
    void dispatch(DispatchDO dispatch, TaskPackageResult taskPackage);
}
