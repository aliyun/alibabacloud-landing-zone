package com.aliyun.autowonder.mcp;

import javax.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/** Standalone Streamable HTTP stream; requests and responses continue over POST. */
@Service
public class McpSseService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpSseService.class);
    private static final Duration HEARTBEAT = Duration.ofSeconds(15);
    private static final Duration LIFETIME = Duration.ofMinutes(5);
    private final TaskScheduler scheduler;
    private final Set<Stream> streams = ConcurrentHashMap.newKeySet();
    private boolean stopped;

    public McpSseService(@Qualifier("mcpSseScheduler") TaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public synchronized SseEmitter open() {
        if (stopped) {
            throw new IllegalStateException();
        }
        Stream stream = new Stream();
        streams.add(stream);
        stream.start();
        return stream.emitter;
    }

    @PreDestroy
    public synchronized void shutdown() {
        stopped = true;
        streams.forEach(Stream::close);
    }

    private final class Stream {
        private final SseEmitter emitter = new SseEmitter(LIFETIME.toMillis());
        private ScheduledFuture<?> heartbeat;
        private ScheduledFuture<?> expiry;
        private boolean closed;

        private synchronized void start() {
            emitter.onCompletion(this::close);
            emitter.onTimeout(this::close);
            emitter.onError(error -> close());
            try {
                emitter.send(SseEmitter.event().comment("connected"));
                Instant now = Instant.now();
                heartbeat = scheduler.scheduleWithFixedDelay(this::heartbeat, now.plus(HEARTBEAT), HEARTBEAT);
                expiry = scheduler.schedule(this::close, now.plus(LIFETIME));
            } catch (IOException | RuntimeException e) {
                close();
            }
        }

        private synchronized void heartbeat() {
            if (closed) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException | RuntimeException e) {
                close();
            }
        }

        private synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            if (expiry != null) {
                expiry.cancel(false);
            }
            streams.remove(this);
            try {
                emitter.complete();
            } catch (RuntimeException disconnected) {
                // The servlet container may already have released the socket during shutdown.
                // Tasks and registration are gone; another stream must still be cleaned up.
                log.debug("MCP SSE completion after transport disposal", disconnected);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SchedulerConfiguration {
        @Bean(name = "mcpSseScheduler")
        ThreadPoolTaskScheduler mcpSseScheduler() {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(2);
            scheduler.setThreadNamePrefix("mcp-sse-");
            scheduler.setRemoveOnCancelPolicy(true);
            return scheduler;
        }
    }
}
