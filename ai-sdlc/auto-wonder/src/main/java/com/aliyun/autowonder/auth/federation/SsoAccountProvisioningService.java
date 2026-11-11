package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Date;

/**
 * 首次 SSO 登录自动开通内部账号：创建无本地密码的普通账号并建立企业身份绑定，
 * 两步在同一事务内完成。不调用普通注册流程（因此没有首个管理员初始化），
 * 不创建工作空间成员关系，也不按邮箱认领或合并已有账号。
 */
@Service
public class SsoAccountProvisioningService {
    static final int MAX_USERNAME_ATTEMPTS = 5;
    private static final int MAX_NICKNAME_LENGTH = 64;
    private static final int MAX_EMAIL_LENGTH = 128;
    private static final int GENERATED_SUFFIX_BYTES = 8;
    private static final String USERNAME_PREFIX = "sso_";

    private final UserDao userDao;
    private final FederatedIdentityDao identityDao;
    private final SecureRandom random = new SecureRandom();

    public SsoAccountProvisioningService(UserDao userDao, FederatedIdentityDao identityDao) {
        this.userDao = userDao;
        this.identityDao = identityDao;
    }

    /**
     * 并发首次登录时唯一键兜底：竞争失败方以 {@link ProvisionRaceException} 结束并整体回滚，
     * 不会留下没有绑定的孤立账号。
     */
    @Transactional
    public FederatedIdentityDO provision(ExternalIdentity identity) {
        FederatedIdentityDO existing = identityDao.findBinding(
                identity.providerId(), identity.issuer(), identity.subject());
        if (existing != null) {
            return existing;
        }
        UserDO user = createUser(identity);
        FederatedIdentityDO binding = new FederatedIdentityDO();
        binding.setProviderId(identity.providerId());
        binding.setIssuer(identity.issuer());
        binding.setSubject(identity.subject());
        binding.setUserId(user.getId());
        binding.setDisplayName(identity.displayName());
        binding.setEmail(identity.email());
        binding.setLastLoginAt(new Date());
        try {
            identityDao.insert(binding);
        } catch (DuplicateKeyException e) {
            throw new ProvisionRaceException(e);
        }
        return binding;
    }

    private UserDO createUser(ExternalIdentity identity) {
        String nickname = normalize(identity.displayName(), MAX_NICKNAME_LENGTH);
        String email = normalize(identity.email(), MAX_EMAIL_LENGTH);
        for (int attempt = 0; attempt < MAX_USERNAME_ATTEMPTS; attempt++) {
            String username = generateUsername();
            UserDO user = new UserDO();
            user.setUsername(username);
            // 显示名缺失时用系统生成的用户名兜底，避免 nickname 为空。
            user.setNickname(nickname == null ? username : nickname);
            user.setEmail(email);
            // 纯 SSO 账号不设置本地密码。
            user.setPasswordHash(null);
            user.setStatus(0);
            try {
                userDao.insert(user);
                return user;
            } catch (DuplicateKeyException e) {
                if (userDao.findByUsername(username) != null) {
                    // 用户名碰撞：换一个生成值重试。
                    continue;
                }
                if (email != null) {
                    // 邮箱唯一键冲突：邮箱留空不阻断开通，也不合并账号。
                    email = null;
                    continue;
                }
                throw e;
            }
        }
        throw new BizException(ErrorCode.FEDERATION_PROVISION_FAILED);
    }

    private String generateUsername() {
        byte[] bytes = new byte[GENERATED_SUFFIX_BYTES];
        random.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(USERNAME_PREFIX);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0x0f, 16));
            builder.append(Character.forDigit(value & 0x0f, 16));
        }
        return builder.toString();
    }

    private static String normalize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    /** 同一外部身份并发首次登录的竞争失败方：调用方回读已建立的绑定或按身份冲突处理。 */
    public static class ProvisionRaceException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ProvisionRaceException(Throwable cause) {
            super("concurrent first login created the same federated identity", cause);
        }
    }
}
