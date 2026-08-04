package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.AlreadyLoggedException;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.im.dto.PlatformImChannelConfigVO;
import com.aliyun.autowonder.im.dto.UpdateDingTalkChannelRequest;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Service
public class PlatformImChannelConfigService {
    private static final Logger log = LoggerFactory.getLogger(PlatformImChannelConfigService.class);

    private final PlatformImChannelConfigDao configDao;
    private final SecretCrypto secretCrypto;

    public PlatformImChannelConfigService(PlatformImChannelConfigDao configDao,
                                          SecretCrypto secretCrypto) {
        this.configDao = configDao;
        this.secretCrypto = secretCrypto;
    }

    public List<PlatformImChannelConfigVO> list(long userId) {
        try {
            List<PlatformImChannelConfigVO> result = new ArrayList<>();
            for (PlatformImChannelConfigDO config : configDao.listActive()) {
                result.add(toVO(config));
            }
            return result;
        } catch (AlreadyLoggedException e) {
            throw e;
        } catch (BizException e) {
            if (isExpected(e)) {
                throw e;
            }
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification platform config read failed operatorId={}", userId, safe);
            throw safe;
        } catch (RuntimeException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification platform config read failed operatorId={}", userId, safe);
            throw safe;
        }
    }

    @Transactional
    public PlatformImChannelConfigVO updateDingTalk(long userId, UpdateDingTalkChannelRequest request) {
        String provider = ImProviderType.DINGTALK.getKey();
        try {
            if (request == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "请求不能为空");
            }
            String appKey = normalizeLength(request.getAppKey(), 128, "appKey");
            String secret = normalizeLength(request.getAppSecret(), 1024, "appSecret");
            String robotCode = normalizeLength(request.getRobotCode(), 128, "robotCode");
            String baseUrl = normalizeHttpsUrl(request.getBaseUrl());
            String credentialRef = secret == null ? null : secretCrypto.encrypt(secret);
            if (credentialRef != null && credentialRef.length() > 1024) {
                throw new BizException(ErrorCode.PARAM_INVALID, "加密凭据引用过长");
            }

            PlatformImChannelConfigDO next = new PlatformImChannelConfigDO();
            next.setProvider(provider);
            next.setEnabled(request.isEnabled() ? 1 : 0);
            next.setAppKey(appKey);
            next.setCredentialRef(credentialRef);
            next.setRobotCode(robotCode);
            next.setBaseUrl(baseUrl);
            next.setCreatorId(userId);
            next.setModifierId(userId);
            configDao.upsert(next);
            PlatformImChannelConfigDO saved = configDao.findByProvider(provider);
            PlatformImChannelConfigDO effective = saved == null ? next : saved;
            if (effective.getEnabled() == 1 && !isComplete(effective)) {
                throw new BizException(ErrorCode.IM_CHANNEL_NOT_READY);
            }
            boolean secretConfigured = hasText(effective.getCredentialRef());
            log.info("IM notification platform config updated provider={} enabled={} secretConfigured={} operatorId={}",
                    provider, effective.getEnabled() == 1, secretConfigured, userId);
            return toVO(effective);
        } catch (AlreadyLoggedException e) {
            throw e;
        } catch (BizException e) {
            if (isExpected(e)) {
                throw e;
            }
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification platform config update failed provider={} operatorId={}",
                    provider, userId, safe);
            throw safe;
        } catch (RuntimeException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification platform config update failed provider={} operatorId={}",
                    provider, userId, safe);
            throw safe;
        }
    }

    public PlatformImChannelConfigDO findEnabled(String provider) {
        String normalizedProvider = ImProviderType.normalize(provider);
        try {
            PlatformImChannelConfigDO config = configDao.findByProvider(normalizedProvider);
            return config != null && Integer.valueOf(1).equals(config.getEnabled()) ? config : null;
        } catch (AlreadyLoggedException e) {
            throw e;
        } catch (BizException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification platform config read failed provider={}",
                    normalizedProvider, safe);
            throw safe;
        } catch (RuntimeException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification platform config read failed provider={}",
                    normalizedProvider, safe);
            throw safe;
        }
    }

    public String decryptSecret(PlatformImChannelConfigDO config) {
        if (config == null || !hasText(config.getCredentialRef())) {
            return null;
        }
        String provider = config.getProvider() == null ? "UNKNOWN" : config.getProvider();
        try {
            return secretCrypto.decrypt(config.getCredentialRef());
        } catch (AlreadyLoggedException e) {
            throw e;
        } catch (BizException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification platform config decrypt failed provider={}",
                    provider, safe);
            throw safe;
        } catch (RuntimeException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM notification platform config decrypt failed provider={}",
                    provider, safe);
            throw safe;
        }
    }

    public boolean isReady(String provider) {
        PlatformImChannelConfigDO config = findEnabled(provider);
        return config != null && isComplete(config);
    }

    private static boolean isComplete(PlatformImChannelConfigDO config) {
        return hasText(config.getAppKey())
                && hasText(config.getCredentialRef())
                && hasText(config.getRobotCode());
    }

    private static PlatformImChannelConfigVO toVO(PlatformImChannelConfigDO config) {
        PlatformImChannelConfigVO vo = new PlatformImChannelConfigVO();
        vo.setProvider(config.getProvider());
        vo.setEnabled(Integer.valueOf(1).equals(config.getEnabled()));
        vo.setAppKey(config.getAppKey());
        vo.setRobotCode(config.getRobotCode());
        vo.setBaseUrl(config.getBaseUrl());
        vo.setSecretConfigured(hasText(config.getCredentialRef()));
        vo.setReady(vo.isEnabled() && isComplete(config));
        return vo;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeLength(String value, int maxLength, String field) {
        String normalized = trimToNull(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + " 长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private static String normalizeHttpsUrl(String value) {
        String normalized = normalizeLength(value, 512, "baseUrl");
        if (normalized == null) {
            return null;
        }
        try {
            URI uri = URI.create(normalized);
            if (!uri.isAbsolute()
                    || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "baseUrl 必须是绝对 HTTPS URL");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isExpected(BizException error) {
        return ErrorCode.PARAM_INVALID.getCode().equals(error.getCode())
                || ErrorCode.NO_PERMISSION.getCode().equals(error.getCode())
                || ErrorCode.IM_CHANNEL_NOT_READY.getCode().equals(error.getCode());
    }
}
