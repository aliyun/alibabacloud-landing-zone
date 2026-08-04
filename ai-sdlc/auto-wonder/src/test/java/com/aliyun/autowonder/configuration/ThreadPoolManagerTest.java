package com.aliyun.autowonder.configuration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ThreadPoolManagerTest {

    @Test
    void sharedPoolsAreMdcAware() {
        assertInstanceOf(MdcAwareThreadPoolExecutor.class, ThreadPoolManager.invokeTaskPool);
        assertInstanceOf(MdcAwareThreadPoolExecutor.class, ThreadPoolManager.asyncTaskThreadPool);
        assertInstanceOf(MdcAwareThreadPoolExecutor.class, ThreadPoolManager.metricCallBackExecutor);
        assertInstanceOf(MdcAwareThreadPoolExecutor.class, ThreadPoolManager.networkCallPool);
    }
}
