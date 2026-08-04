package com.aliyun.autowonder.access;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireOrgAccess {
    OrgAccessLevel value() default OrgAccessLevel.READ_ONLY;

    String action() default "访问组织资源";
}
