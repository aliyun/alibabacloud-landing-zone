package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.taskpackage.TaskPackageResult;
import org.junit.jupiter.api.Test;

class LoggingDispatchTransportTest {

    @Test
    void dispatchDoesNotThrow() {
        LoggingDispatchTransport transport = new LoggingDispatchTransport();
        DispatchDO d = new DispatchDO();
        d.setId(1L);
        d.setTenantId(9L);
        d.setExecutorId(7L);
        TaskPackageResult pkg = new TaskPackageResult("oss://bucket/1.zip", "md5", 100L, "http://dl", "deadbeef");
        transport.dispatch(d, pkg); // no exception
    }

    @Test
    void dispatchToleratesNullPackage() {
        LoggingDispatchTransport transport = new LoggingDispatchTransport();
        DispatchDO d = new DispatchDO();
        d.setId(2L);
        transport.dispatch(d, null);
    }
}
