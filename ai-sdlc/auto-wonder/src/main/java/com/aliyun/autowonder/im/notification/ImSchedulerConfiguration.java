package com.aliyun.autowonder.im.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ImSchedulerConfiguration {

    @Bean(name = "imTaskScheduler", destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler imTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("im-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Bean
    public ImScheduledTaskRegistrar imScheduledTaskRegistrar(TaskScheduler imTaskScheduler,
                                                              ImNotificationWorker worker,
                                                              ImNotificationProperties properties) {
        return new ImScheduledTaskRegistrar(imTaskScheduler, worker, properties);
    }

    static class ImScheduledTaskRegistrar extends ScheduledTaskRegistrar {

        ImScheduledTaskRegistrar(TaskScheduler scheduler,
                                 ImNotificationWorker worker,
                                 ImNotificationProperties properties) {
            setTaskScheduler(scheduler);
            addFixedDelayTask(new FixedDelayTask(
                    worker::pollNew, properties.getPollDelayMs(), properties.getPollDelayMs()));
            addFixedDelayTask(new FixedDelayTask(
                    worker::recoverStale, properties.getRecoveryDelayMs(), properties.getRecoveryDelayMs()));
        }
    }
}
