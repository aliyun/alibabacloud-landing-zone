package com.aliyun.autowonder.websocket;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component
public class WsSpringContext implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }

    public static <T> T getBean(Class<T> clazz) {
        return context.getBean(clazz);
    }

    public static <T> T safeGetBean(Class<T> clazz) {
        if (context == null) {
            return null;
        }
        try {
            return context.getBean(clazz);
        } catch (Exception e) {
            return null;
        }
    }
}
