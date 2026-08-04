package com.aliyun.autowonder.access;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Aspect
@Component
public class OrgAccessAspect {

    @Around("@within(com.aliyun.autowonder.access.RequireOrgAccess) || "
            + "@annotation(com.aliyun.autowonder.access.RequireOrgAccess) || "
            + "execution(@com.aliyun.autowonder.access.RequireOrgAccess * *(..)) || "
            + "execution(* (@com.aliyun.autowonder.access.RequireOrgAccess *).*(..))")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        RequireOrgAccess requirement = resolveRequirement(joinPoint);
        if (requirement == null) {
            return joinPoint.proceed();
        }
        AutoWonderContext context = AutoWonderContext.get();
        if (context.getUserId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (context.getCurrentOrgId() == null || context.getOrgAccessLevel() == null) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }

        OrgAccessLevel current = context.getOrgAccessLevel();
        if (!current.allows(requirement.value())) {
            throw new OrgAccessDeniedException(
                    current, requirement.value(), requirement.action());
        }
        return joinPoint.proceed();
    }

    private RequireOrgAccess resolveRequirement(ProceedingJoinPoint joinPoint) {
        Method signatureMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object target = joinPoint.getTarget();
        Class<?> targetClass = target == null
                ? signatureMethod.getDeclaringClass()
                : AopUtils.getTargetClass(target);
        Method concreteMethod = AopUtils.getMostSpecificMethod(signatureMethod, targetClass);

        RequireOrgAccess requirement = AnnotatedElementUtils.findMergedAnnotation(
                concreteMethod, RequireOrgAccess.class);
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(
                    signatureMethod, RequireOrgAccess.class);
        }
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(
                    targetClass, RequireOrgAccess.class);
        }
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(
                    signatureMethod.getDeclaringClass(), RequireOrgAccess.class);
        }
        return requirement;
    }
}
