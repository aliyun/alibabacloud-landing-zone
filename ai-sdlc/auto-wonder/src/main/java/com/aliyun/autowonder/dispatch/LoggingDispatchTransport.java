package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.taskpackage.TaskPackageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * B2a default transport: logs instead of pushing. B2b supplies a concrete
 * WebSocket-backed {@link DispatchTransport} bean, which overrides this one.
 * Registered as the fallback bean by {@link DispatchTransportConfig}.
 */
public class LoggingDispatchTransport implements DispatchTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingDispatchTransport.class);

    @Override
    public void dispatch(DispatchDO dispatch, TaskPackageResult taskPackage) {
        String ossRef = taskPackage == null ? null : taskPackage.getOssRef();
        LOGGER.info("dispatch[no-transport] id={} executorId={} pkg={}",
                dispatch.getId(), dispatch.getExecutorId(), ossRef);
    }
}
