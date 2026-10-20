package com.aliyun.autowonder.im;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.AlreadyLoggedException;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.im.dto.UserImIdentityVO;
import com.aliyun.autowonder.user.UserSettingService;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
public class UserImIdentityService {
    private static final Logger log = LoggerFactory.getLogger(UserImIdentityService.class);

    private final UserImIdentityDao identityDao;
    private final PlatformImChannelConfigService channelConfigService;
    private final ImProviderRegistry providerRegistry;
    private final PlatformBrandingService brandingService;
    private final UserSettingService userSettingService;

    public UserImIdentityService(UserImIdentityDao identityDao,
                                 PlatformImChannelConfigService channelConfigService) {
        this(identityDao, channelConfigService, null, null);
    }

    public UserImIdentityService(UserImIdentityDao identityDao,
                                 PlatformImChannelConfigService channelConfigService,
                                 ImProviderRegistry providerRegistry,
                                 PlatformBrandingService brandingService) {
        this(identityDao, channelConfigService, providerRegistry, brandingService, null);
    }

    @Autowired
    public UserImIdentityService(UserImIdentityDao identityDao, PlatformImChannelConfigService channelConfigService,
            ImProviderRegistry providerRegistry, PlatformBrandingService brandingService, UserSettingService userSettingService) {
        this.userSettingService = userSettingService;
        this.identityDao = identityDao;
        this.channelConfigService = channelConfigService;
        this.providerRegistry = providerRegistry;
        this.brandingService = brandingService;
    }

    public List<UserImIdentityVO> list(long userId) {
        try {
            List<UserImIdentityVO> result = new ArrayList<>();
            String provider = channelConfigService.selectedProvider();
            UserImIdentityDO identity = identityDao.find(userId, provider);
            boolean ready = channelConfigService.isReady(provider);
            result.add(identity == null ? emptyVO(provider, ready) : toVO(identity, ready));
            return result;
        } catch (AlreadyLoggedException e) {
            throw e;
        } catch (BizException e) {
            if (isExpected(e)) {
                throw e;
            }
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification user identity read failed userId={}", userId, safe);
            throw safe;
        } catch (RuntimeException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification user identity read failed userId={}", userId, safe);
            throw safe;
        }
    }

    @Transactional
    public UserImIdentityVO update(long userId, String provider, String externalUserId) {
        return update(userId, provider, externalUserId, userId);
    }

    /**
     * 管理端（平台用户管理）代改工号时 operatorId 才是操作人，落库的 creator/modifier 必须记录
     * 实际操作者而不是被改用户。
     */
    @Transactional
    public UserImIdentityVO update(long userId, String provider, String externalUserId, long operatorId) {
        String normalizedProvider = ImProviderType.normalize(provider);
        try {
            channelConfigService.requireSelected(normalizedProvider);
            String normalizedExternalId = trimToNull(externalUserId);
            if (normalizedExternalId != null && normalizedExternalId.length() > 256) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID, "im.external_user_id_too_long",
                        new Object[0], "externalUserId 长度不能超过 256");
            }
            if (normalizedExternalId == null) {
                identityDao.softDelete(userId, normalizedProvider, operatorId);
                log.info("IM notification user identity updated provider={} userId={} configured=false externalIdFingerprint=none",
                        normalizedProvider, userId);
                return emptyVO(normalizedProvider, channelConfigService.isReady(normalizedProvider));
            }

            UserImIdentityDO identity = new UserImIdentityDO();
            identity.setUserId(userId);
            identity.setProvider(normalizedProvider);
            identity.setExternalUserId(normalizedExternalId);
            identity.setCreatorId(operatorId);
            identity.setModifierId(operatorId);
            identityDao.upsert(identity);
            log.info("IM notification user identity updated provider={} userId={} configured=true externalIdFingerprint={}",
                    normalizedProvider, userId, fingerprint(normalizedExternalId));
            return toVO(identity, channelConfigService.isReady(normalizedProvider));
        } catch (AlreadyLoggedException e) {
            throw e;
        } catch (BizException e) {
            if (isExpected(e)) {
                throw e;
            }
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification user identity update failed provider={} userId={}",
                    normalizedProvider, userId, safe);
            throw safe;
        } catch (RuntimeException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification user identity update failed provider={} userId={}",
                    normalizedProvider, userId, safe);
            throw safe;
        }
    }

    public UserImIdentityDO find(long userId, String provider) {
        String normalizedProvider = ImProviderType.normalize(provider);
        try {
            return identityDao.find(userId, normalizedProvider);
        } catch (AlreadyLoggedException e) {
            throw e;
        } catch (BizException e) {
            if (isExpected(e)) {
                throw e;
            }
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification user identity read failed provider={} userId={}",
                    normalizedProvider, userId, safe);
            throw safe;
        } catch (RuntimeException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification user identity read failed provider={} userId={}",
                    normalizedProvider, userId, safe);
            throw safe;
        }
    }

    public UserImIdentityVO capability(long userId, String provider) {
        String normalizedProvider = ImProviderType.normalize(provider);
        try {
            UserImIdentityDO identity = identityDao.find(userId, normalizedProvider);
            boolean platformReady = channelConfigService.isReady(normalizedProvider);
            return identity == null
                    ? emptyVO(normalizedProvider, platformReady)
                    : toVO(identity, platformReady);
        } catch (AlreadyLoggedException e) {
            throw e;
        } catch (BizException e) {
            if (isExpected(e)) {
                throw e;
            }
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification user identity capability read failed provider={} userId={}",
                    normalizedProvider, userId, safe);
            throw safe;
        } catch (RuntimeException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification user identity capability read failed provider={} userId={}",
                    normalizedProvider, userId, safe);
            throw safe;
        }
    }

    public void sendTest(long userId, String provider) {
        String normalizedProvider = ImProviderType.normalize(provider);
        channelConfigService.requireSelected(normalizedProvider);
        String recipientFingerprint = "none";
        log.info("IM notification test requested provider={} userId={} recipientFingerprint={}",
                normalizedProvider, userId, recipientFingerprint);
        try {
            UserImIdentityDO identity = identityDao.find(userId, normalizedProvider);
            if (identity == null || !hasText(identity.getExternalUserId())) {
                throw new BizException(ErrorCode.IM_IDENTITY_NOT_CONFIGURED);
            }
            recipientFingerprint = fingerprint(identity.getExternalUserId());
            if (!channelConfigService.isReady(normalizedProvider)) {
                throw new BizException(ErrorCode.IM_CHANNEL_NOT_READY);
            }

            String brand = brandingService.publicConfig().getPlatformName();
            if (!hasText(brand)) {
                brand = PlatformBrandingService.DEFAULT_PLATFORM_NAME;
            }
            Locale locale = userSettingService == null ? Locale.CHINA : userSettingService.recipientLocale(userId);
            String title = SystemMessages.render("im.test.success_title", new Object[]{brand},
                    brand + " 协作通知测试成功", locale);
            providerRegistry.require(normalizedProvider).send(new ImSendCommand(
                    normalizedProvider,
                    identity.getExternalUserId(),
                    title,
                    SystemMessages.render("im.test.success_body", new Object[]{title},
                            "## " + title + "\n\n你的 IM 协作通知配置可正常使用。", locale)));
            log.info("IM notification test delivered provider={} userId={} recipientFingerprint={}",
                    normalizedProvider, userId, recipientFingerprint);
        } catch (AlreadyLoggedException e) {
            throw e;
        } catch (BizException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification test failed provider={} userId={} recipientFingerprint={}",
                    normalizedProvider, userId, recipientFingerprint, safe);
            throw e.getMessageKey() == null
                    ? new BizException(e.getCode(), e.getMessage(), safe)
                    : BizException.keyed(e.getCode(), e.getMessageKey(), e.getMessageArgs(), e.getMessage(), safe);
        } catch (ImDeliveryException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification test failed provider={} userId={} recipientFingerprint={} "
                            + "retryable={} providerCode={} providerRequestId={}",
                    normalizedProvider, userId, recipientFingerprint, e.isRetryable(),
                    safeValue(e.getProviderCode()), safeValue(e.getProviderRequestId()), safe);
            throw new BizException(ErrorCode.IM_TEST_SEND_FAILED, safe);
        } catch (RuntimeException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification test failed provider={} userId={} recipientFingerprint={}",
                    normalizedProvider, userId, recipientFingerprint, safe);
            throw new BizException(ErrorCode.IM_TEST_SEND_FAILED, safe);
        }
    }

    private static UserImIdentityVO toVO(UserImIdentityDO identity, boolean platformReady) {
        UserImIdentityVO vo = new UserImIdentityVO();
        vo.setProvider(identity.getProvider());
        vo.setExternalUserId(identity.getExternalUserId());
        boolean configured = hasText(identity.getExternalUserId());
        vo.setConfigured(configured);
        vo.setPlatformReady(platformReady);
        vo.setTestAvailable(configured && platformReady);
        return vo;
    }

    private static UserImIdentityVO emptyVO(String provider, boolean platformReady) {
        UserImIdentityVO vo = new UserImIdentityVO();
        vo.setProvider(provider);
        vo.setConfigured(false);
        vo.setPlatformReady(platformReady);
        vo.setTestAvailable(false);
        return vo;
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String safeValue(String value) {
        return value == null ? "unknown" : value;
    }

    private static boolean isExpected(BizException error) {
        return ErrorCode.PARAM_INVALID.getCode().equals(error.getCode());
    }
}
