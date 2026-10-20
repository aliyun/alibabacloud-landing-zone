package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 普通用户可见的 SSO 只读概览：只投影名称、协议、启用态与「是否已配置密钥」。
 * 与 {@link FederationConnectionVO} 分离，避免把管理面字段（端点、证书、客户端 ID、掩码）带出。
 */
@Getter
@Setter
public class FederationOverviewVO {
    private List<Idp> idps;
    private List<Connection> connections;

    @Getter
    @Setter
    public static class Idp {
        private Long id;
        private String name;
        private Integer enabled;
    }

    @Getter
    @Setter
    public static class Connection {
        private Long id;
        private Long idpId;
        private String idpName;
        private String name;
        private String protocol;
        private Integer enabled;
        private Boolean clientSecretConfigured;
    }
}
