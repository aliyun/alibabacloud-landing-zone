package com.aliyun.autowonder.scheduledtask;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ScheduledTaskSpringConstructorInjectionTest {

    @Test
    void springSelectsTheProductionConstructorsForScheduledTaskComponents() {
        assertProductionConstructor(ScheduledTaskTriggerService.class, 6);
        assertProductionConstructor(ScheduledTaskScheduler.class, 6);
        assertProductionConstructor(ScheduledTaskRunCompensationTask.class, 6);
        assertProductionConstructor(ScheduledTaskMetrics.class, 3);
        assertProductionConstructor(ScheduledTaskRunRecoveryService.class, 4);
        assertProductionConstructor(ScheduledTaskService.class, 6);
    }

    private static void assertProductionConstructor(Class<?> beanType, int parameterCount) {
        AutowiredAnnotationBeanPostProcessor processor = new AutowiredAnnotationBeanPostProcessor();
        Constructor<?>[] candidates = processor.determineCandidateConstructors(beanType, beanType.getName());

        assertNotNull(candidates, () -> "Spring found no injectable constructor for " + beanType.getName());
        assertEquals(1, candidates.length, () -> "Spring found ambiguous constructors for " + beanType.getName());
        assertEquals(parameterCount, candidates[0].getParameterCount(),
                () -> "Spring selected the test-only constructor for " + beanType.getName());
    }
}
