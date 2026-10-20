package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

/** 一次性票据请求体：登录票据换会话、绑定票据认领都用同一形状。 */
@Getter
@Setter
public class SsoTicketRequest {
    private String ticket;
}
