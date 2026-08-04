package com.aliyun.autowonder.websocket;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Configuration
public class WebSocketConfig {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final RedisManager redisManager;
    private final NodeMailboxListener mailboxListener;
    private final BrowserRealtimeSubscriberManager subscriberManager;
    private volatile Thread subscriberThread;
    private volatile Thread conversationSubscriberThread;
    private volatile JedisPubSub pubSub;
    private volatile JedisPubSub conversationPubSub;

    public WebSocketConfig(RedisManager redisManager, NodeMailboxListener mailboxListener,
            BrowserRealtimeSubscriberManager subscriberManager) {
        this.redisManager = redisManager;
        this.mailboxListener = mailboxListener;
        this.subscriberManager = subscriberManager;
    }

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        ServerEndpointExporter exporter = new ServerEndpointExporter();
        exporter.setAnnotatedEndpointClasses(
                ExecutorWsEndpoint.class,
                BrowserRealtimeEndpoint.class
        );
        return exporter;
    }

    @PostConstruct
    public void startSubscriber() {
        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                mailboxListener.onMessage(channel, message);
            }
        };
        subscriberThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = redisManager.getJedisPool().getResource()) {
                    jedis.subscribe(pubSub, WsDispatchTransport.BROADCAST_CHANNEL);
                } catch (Exception e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        log.warn("Redis subscriber disconnected, reconnecting in 3s", e);
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }, "ws-mailbox-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();

        conversationPubSub = new JedisPubSub() {
            @Override
            public void onMessage(String redisChannel, String message) {
                try {
                    JSONObject frame = JSON.parseObject(message);
                    if (frame == null) return;
                    String targetChannel = frame.getString("channel");
                    if (targetChannel != null) {
                        subscriberManager.deliverToChannel(targetChannel, message);
                    }
                } catch (Exception e) {
                    log.warn("conversation realtime redis delivery failed: {}", e.getMessage());
                }
            }
        };
        conversationSubscriberThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Jedis jedis = redisManager.getJedisPool().getResource()) {
                    jedis.subscribe(conversationPubSub, ConversationRealtimePublisher.REDIS_CHANNEL);
                } catch (Exception e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        log.warn("Conversation Redis subscriber disconnected, reconnecting in 3s", e);
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }, "ws-conversation-subscriber");
        conversationSubscriberThread.setDaemon(true);
        conversationSubscriberThread.start();
    }

    @PreDestroy
    public void stopSubscriber() {
        if (pubSub != null) {
            try {
                pubSub.unsubscribe();
            } catch (Exception ignore) {
            }
        }
        if (conversationPubSub != null) {
            try {
                conversationPubSub.unsubscribe();
            } catch (Exception ignore) {
            }
        }
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
        if (conversationSubscriberThread != null) {
            conversationSubscriberThread.interrupt();
        }
    }
}
