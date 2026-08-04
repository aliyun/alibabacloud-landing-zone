package com.aliyun.autowonder.configuration;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.*;

public class ThreadPoolManager {

    public static ExecutorService invokeTaskPool = new MdcAwareThreadPoolExecutor(10, Runtime.getRuntime().availableProcessors() * 100, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadFactoryBuilder().setNameFormat(
            "autowonder-io-invoke-%d-thread").setDaemon(true).build());

    public static ExecutorService asyncTaskThreadPool = new MdcAwareThreadPoolExecutor(Runtime.getRuntime().availableProcessors() * 2, Runtime.getRuntime().availableProcessors() * 4, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000), new ThreadFactoryBuilder().setNameFormat(
            "autowonder-async-task-%d-thread").setDaemon(true).build());

    public static ThreadPoolExecutor metricCallBackExecutor = new MdcAwareThreadPoolExecutor(1, Runtime.getRuntime().availableProcessors(),
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(100), new ThreadFactoryBuilder()
            .setNameFormat("metrics-callback-pool-%d").build(), new ThreadPoolExecutor.CallerRunsPolicy());

    public static ExecutorService networkCallPool = new MdcAwareThreadPoolExecutor(Runtime.getRuntime().availableProcessors() * 4, Runtime.getRuntime().availableProcessors() * 8, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(2000), new ThreadFactoryBuilder().setNameFormat(
            "autowonder-network-call-%d-thread").setDaemon(true).build());
}
