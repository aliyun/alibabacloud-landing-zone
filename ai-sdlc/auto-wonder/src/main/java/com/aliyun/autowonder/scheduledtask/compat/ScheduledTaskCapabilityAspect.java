package com.aliyun.autowonder.scheduledtask.compat;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
@Order(100)
public class ScheduledTaskCapabilityAspect {

    private final ScheduledTaskCapabilityGuard guard;

    public ScheduledTaskCapabilityAspect(ScheduledTaskCapabilityGuard guard) {
        this.guard = guard;
    }

    @Around("@within(com.aliyun.autowonder.scheduledtask.compat.RequiresScheduledTaskCapability) || "
            + "@annotation(com.aliyun.autowonder.scheduledtask.compat.RequiresScheduledTaskCapability) || "
            + "execution(@com.aliyun.autowonder.scheduledtask.compat.RequiresScheduledTaskCapability * *(..)) || "
            + "execution(* (@com.aliyun.autowonder.scheduledtask.compat.RequiresScheduledTaskCapability *).*(..))")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        RequiresScheduledTaskCapability requirement = resolveRequirement(joinPoint);
        if (requirement == null) {
            return joinPoint.proceed();
        }
        guard.requireAvailable(requirement.entry());
        return joinPoint.proceed();
    }

    private RequiresScheduledTaskCapability resolveRequirement(ProceedingJoinPoint joinPoint) {
        Method signatureMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object target = joinPoint.getTarget();
        Class<?> targetClass = target == null
                ? signatureMethod.getDeclaringClass()
                : AopUtils.getTargetClass(target);
        Method concreteMethod = AopUtils.getMostSpecificMethod(signatureMethod, targetClass);

        RequiresScheduledTaskCapability requirement = AnnotatedElementUtils.findMergedAnnotation(
                concreteMethod, RequiresScheduledTaskCapability.class);
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(
                    signatureMethod, RequiresScheduledTaskCapability.class);
        }
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(
                    targetClass, RequiresScheduledTaskCapability.class);
        }
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(
                    signatureMethod.getDeclaringClass(), RequiresScheduledTaskCapability.class);
        }
        return requirement;
    }
}
