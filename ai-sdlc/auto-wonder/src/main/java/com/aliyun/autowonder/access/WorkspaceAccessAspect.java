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
public class WorkspaceAccessAspect {

    @Around("@within(com.aliyun.autowonder.access.RequireWorkspaceAccess) || "
            + "@annotation(com.aliyun.autowonder.access.RequireWorkspaceAccess) || "
            + "execution(@com.aliyun.autowonder.access.RequireWorkspaceAccess * *(..)) || "
            + "execution(* (@com.aliyun.autowonder.access.RequireWorkspaceAccess *).*(..))")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        RequireWorkspaceAccess requirement = resolveRequirement(joinPoint);
        if (requirement == null) {
            return joinPoint.proceed();
        }
        AutoWonderContext context = AutoWonderContext.get();
        if (context.getUserId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (context.getCurrentWorkspaceId() == null || context.getWorkspaceAccessLevel() == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }

        WorkspaceAccessLevel current = context.getWorkspaceAccessLevel();
        if (!current.allows(requirement.value())) {
            throw new WorkspaceAccessDeniedException(
                    current, requirement.value(), requirement.action());
        }
        return joinPoint.proceed();
    }

    private RequireWorkspaceAccess resolveRequirement(ProceedingJoinPoint joinPoint) {
        Method signatureMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object target = joinPoint.getTarget();
        Class<?> targetClass = target == null
                ? signatureMethod.getDeclaringClass()
                : AopUtils.getTargetClass(target);
        Method concreteMethod = AopUtils.getMostSpecificMethod(signatureMethod, targetClass);

        RequireWorkspaceAccess requirement = AnnotatedElementUtils.findMergedAnnotation(
                concreteMethod, RequireWorkspaceAccess.class);
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(
                    signatureMethod, RequireWorkspaceAccess.class);
        }
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(
                    targetClass, RequireWorkspaceAccess.class);
        }
        if (requirement == null) {
            requirement = AnnotatedElementUtils.findMergedAnnotation(
                    signatureMethod.getDeclaringClass(), RequireWorkspaceAccess.class);
        }
        return requirement;
    }
}
