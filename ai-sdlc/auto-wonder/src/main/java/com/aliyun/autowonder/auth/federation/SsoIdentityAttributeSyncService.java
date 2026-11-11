package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.im.ImProviderType;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SSO 认证属性到内部账号资料的同步。当前只同步飞书用户 ID，且必须同时满足：
 * 连接配置了属性名、认证结果携带非空值、平台当前消息通道为飞书。
 * 同步不参与登录核心事务：失败只记录脱敏日志，不影响登录或回滚账号绑定。
 */
@Service
public class SsoIdentityAttributeSyncService {
    private static final Logger log = LoggerFactory.getLogger(SsoIdentityAttributeSyncService.class);
    private static final int MAX_EXTERNAL_USER_ID_LENGTH = 256;

    private final PlatformImChannelConfigService channelConfigService;
    private final UserImIdentityService userImIdentityService;

    public SsoIdentityAttributeSyncService(PlatformImChannelConfigService channelConfigService,
                                           UserImIdentityService userImIdentityService) {
        this.channelConfigService = channelConfigService;
        this.userImIdentityService = userImIdentityService;
    }

    /** 核心建号/绑定事务提交后调用；任何异常都被吞掉，避免阻断登录。 */
    public void syncFeishu(Long userId, FederationConnectionDO connection, ExternalIdentity identity) {
        if (userId == null || connection == null || identity == null) {
            return;
        }
        String claim = trimToNull(connection.getFeishuUserIdClaim());
        String value = trimToNull(identity.feishuUserId());
        if (claim == null || value == null) {
            return;
        }
        try {
            if (!ImProviderType.FEISHU.getKey().equals(channelConfigService.selectedProvider())) {
                return;
            }
            if (value.length() > MAX_EXTERNAL_USER_ID_LENGTH) {
                log.warn("sso identity sync skipped userId={} claim={} reason=value_too_long", userId, claim);
                return;
            }
            userImIdentityService.update(userId, ImProviderType.FEISHU.getKey(), value, userId);
            log.info("sso identity sync completed userId={} provider=FEISHU claim={} source=sso", userId, claim);
        } catch (RuntimeException e) {
            // 不输出原始断言、令牌或完整飞书 ID，只留可诊断的异常类型。
            log.warn("sso identity sync failed userId={} provider=FEISHU claim={} source=sso error={}",
                    userId, claim, e.getClass().getSimpleName());
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
