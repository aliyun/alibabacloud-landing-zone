package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.AlreadyLoggedException;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.im.dto.GroupChatConfigVO;
import com.aliyun.autowonder.im.dto.PlatformImChannelConfigVO;
import com.aliyun.autowonder.im.dto.PlatformImSelectionVO;
import com.aliyun.autowonder.im.dto.UpdateDingTalkChannelRequest;
import com.aliyun.autowonder.im.dto.UpdateGroupChatConfigRequest;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
public class PlatformImChannelConfigService {
    private static final Logger log = LoggerFactory.getLogger(PlatformImChannelConfigService.class);
    private static final Pattern FEISHU_APP_ID = Pattern.compile("cli_[A-Za-z0-9]+");

    private final PlatformImChannelConfigDao configDao;
    private final SecretCrypto secretCrypto;
    private final ObjectMapper objectMapper;
    private final ImChannelIdentityGuard identityGuard;

    public PlatformImChannelConfigService(PlatformImChannelConfigDao configDao,
                                   SecretCrypto secretCrypto) {
        this(configDao, secretCrypto, new ObjectMapper());
    }

    public PlatformImChannelConfigService(PlatformImChannelConfigDao configDao,
                                          SecretCrypto secretCrypto,
                                          ObjectMapper objectMapper) {
        this(configDao, secretCrypto, objectMapper, null);
    }

    @Autowired
    public PlatformImChannelConfigService(PlatformImChannelConfigDao configDao,
                                          SecretCrypto secretCrypto,
                                          ObjectMapper objectMapper,
                                          ImChannelIdentityGuard identityGuard) {
        this.configDao = configDao;
        this.secretCrypto = secretCrypto;
        this.objectMapper = objectMapper;
        this.identityGuard = identityGuard;
    }

    public List<PlatformImChannelConfigVO> list(long userId) {
        try {
            List<PlatformImChannelConfigVO> result = new ArrayList<>();
            String selected = selectedProvider();
            List<PlatformImChannelConfigDO> configs =
                    configDao.listByPurpose(ImChannelPurpose.NOTIFY.name());
            for (ImProviderType type : ImProviderType.values()) {
                PlatformImChannelConfigDO config = configs == null ? null
                        : configs.stream()
                                .filter(item -> type.getKey().equals(item.getProvider()))
                                .findFirst().orElse(null);
                if (config == null) {
                    config = new PlatformImChannelConfigDO();
                    config.setProvider(type.getKey());
                    config.setPurpose(ImChannelPurpose.NOTIFY.name());
                    config.setEnabled(0);
                }
                PlatformImChannelConfigVO vo = toVO(config);
                vo.setSelected(selected.equals(type.getKey()));
                result.add(vo);
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
        return update(userId, "DINGTALK", request);
    }

    public String selectedProvider() {
        String selected = configDao.selectedProvider();
        return selected == null ? "DINGTALK" : ImProviderType.normalize(selected);
    }

    public String selectedProviderOrNull() {
        String selected = configDao.selectedProvider();
        return selected == null || selected.isBlank() ? null
                : ImProviderType.normalize(selected);
    }

    public PlatformImSelectionVO selection() {
        ImSelectionDO row = configDao.selection();
        if (row == null) {
            PlatformImSelectionVO vo = new PlatformImSelectionVO();
            vo.setProvider(selectedProvider());
            return vo;
        }
        return toSelectionVO(row);
    }

    public long selectionGeneration() {
        ImSelectionDO row = configDao.selection();
        return row == null || row.getGeneration() == null ? 0 : row.getGeneration();
    }

    public Date selectionActivatedAt() {
        ImSelectionDO row = configDao.selection();
        return row == null ? null : row.getActivatedAt();
    }

    public ImSelectionDO lockSelection() {
        return configDao.lockSelection();
    }

    public void requireSelected(String provider) {
        if (!selectedProvider().equals(ImProviderType.normalize(provider))) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "im.channel_selected_required", new Object[0], "请使用平台当前选择的 IM 渠道");
        }
    }

    @Transactional
    public PlatformImChannelConfigVO update(long userId, String provider, UpdateDingTalkChannelRequest request) {
        provider = ImProviderType.normalize(provider);
        try {
            if (request == null) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID, "im.request_required", new Object[0], "请求不能为空");
            }
            String appKey = normalizeLength(request.getAppKey(), 128, "appKey");
            String appSecret = normalizeLength(request.getAppSecret(), 1024, "appSecret");
            String robotCode = normalizeLength(request.getRobotCode(), 128, "robotCode");
            String baseUrl = normalizeHttpsUrl(request.getBaseUrl());
            boolean enabled = request.isEnabled();
            String credentialRef = appSecret == null ? null
                    : normalizeCredentialRef(secretCrypto.encrypt(appSecret));

            configDao.lockSelection();
            PlatformImChannelConfigDO existing = configDao.findByProviderAndPurpose(
                    provider, ImChannelPurpose.NOTIFY.name());
            rejectSharedIdentityConflict(provider, appKey, robotCode, existing);

            PlatformImChannelConfigDO next = new PlatformImChannelConfigDO();
            next.setProvider(provider);
            next.setPurpose(ImChannelPurpose.NOTIFY.name());
            next.setEnabled(enabled ? 1 : 0);
            next.setRouteVersion(nextRouteVersion(existing, enabled, appKey, robotCode));
            next.setAppKey(appKey);
            next.setCredentialRef(credentialRef);
            next.setRobotCode(robotCode);
            next.setBaseUrl(baseUrl);
            next.setCreatorId(userId);
            next.setModifierId(userId);
            configDao.upsert(next);
            PlatformImChannelConfigDO saved = configDao.findByProviderAndPurpose(
                    provider, ImChannelPurpose.NOTIFY.name());
            PlatformImChannelConfigDO effective = saved == null ? next : saved;
            if (effective.getEnabled() == 1 && !isNotifyComplete(effective)) {
                throw new BizException(ErrorCode.IM_CHANNEL_NOT_READY);
            }
            configDao.disableOthers(provider, ImChannelPurpose.NOTIFY.name(), userId);
            if (!provider.equals(selectedProviderOrNull())) {
                configDao.switchProvider(provider);
            } else {
                configDao.selectProvider(provider);
            }
            boolean secretConfigured = hasText(effective.getCredentialRef());
            log.info("IM notification platform config updated provider={} enabled={} secretConfigured={} operatorId={}",
                    provider, effective.getEnabled() == 1, secretConfigured, userId);
            PlatformImChannelConfigVO vo = toVO(effective);
            vo.setSelected(true);
            return vo;
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

    @Transactional
    public PlatformImSelectionVO selectProvider(long userId, String provider,
                                                Long expectedGeneration) {
        String normalized = ImProviderType.normalize(provider);
        try {
            ImSelectionDO current = configDao.lockSelection();
            long generation = current == null || current.getGeneration() == null
                    ? 0 : current.getGeneration();
            if (expectedGeneration != null && expectedGeneration != generation) {
                throw BizException.keyed(ErrorCode.CONFLICT,
                        "im.selection_generation_conflict", new Object[0],
                        "IM 渠道选择已被修改，请刷新后重试");
            }
            String currentProvider = current == null ? null : current.getProvider();
            if (!normalized.equals(currentProvider)) {
                configDao.disableOthers(normalized, ImChannelPurpose.NOTIFY.name(), userId);
                configDao.switchProvider(normalized);
                current = configDao.selection();
                log.info("IM channel selection switched provider={} generation={} operatorId={}",
                        normalized, current == null ? null : current.getGeneration(), userId);
            } else {
                configDao.selectProvider(normalized);
            }
            if (current == null) {
                PlatformImSelectionVO vo = new PlatformImSelectionVO();
                vo.setProvider(normalized);
                vo.setGeneration(generation);
                return vo;
            }
            return toSelectionVO(current);
        } catch (AlreadyLoggedException e) {
            throw e;
        } catch (BizException e) {
            if (isExpected(e)) {
                throw e;
            }
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM channel selection update failed provider={} operatorId={}",
                    normalized, userId, safe);
            throw safe;
        } catch (RuntimeException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM channel selection update failed provider={} operatorId={}",
                    normalized, userId, safe);
            throw safe;
        }
    }

    public GroupChatConfigVO groupChat(String provider) {
        String normalized = ImProviderType.normalize(provider);
        try {
            PlatformImChannelConfigDO config = configDao.findByProviderAndPurpose(
                    normalized, ImChannelPurpose.GROUP_CHAT.name());
            GroupChatConfigVO vo = toGroupChatVO(normalized, config);
            vo.setSelected(normalized.equals(selectedProvider()));
            ImSelectionDO selection = configDao.selection();
            vo.setSelectionGeneration(selection == null || selection.getGeneration() == null
                    ? 0 : selection.getGeneration());
            return vo;
        } catch (AlreadyLoggedException e) {
            throw e;
        } catch (BizException e) {
            if (isExpected(e)) {
                throw e;
            }
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM group-chat platform config read failed provider={}", normalized, safe);
            throw safe;
        } catch (RuntimeException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM group-chat platform config read failed provider={}", normalized, safe);
            throw safe;
        }
    }

    @Transactional
    public GroupChatConfigVO updateGroupChat(long userId, String provider,
                                             UpdateGroupChatConfigRequest request) {
        String normalized = ImProviderType.normalize(provider);
        try {
            if (request == null) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID,
                        "im.request_required", new Object[0], "请求不能为空");
            }
            configDao.lockSelection();
            PlatformImChannelConfigDO existing = configDao.findByProviderAndPurpose(
                    normalized, ImChannelPurpose.GROUP_CHAT.name());
            if (request.getVersion() != null && existing != null
                    && !request.getVersion().equals(existing.getVersion())) {
                throw BizException.keyed(ErrorCode.CONFLICT,
                        "im.channel_version_conflict", new Object[0],
                        "群聊机器人配置已被修改，请刷新后重试");
            }

            Identity identity = resolveIdentity(normalized, request, existing);
            rejectNotifyIdentityConflict(normalized, identity, existing);
            if (identityGuard != null) {
                identityGuard.assertGroupChatIdentityAvailable(
                        normalized, identity.appKey(), identity.robotCode());
            }

            boolean identityChanged = identityDiffers(existing, identity);
            boolean newApplication = identityChanged
                    && !Objects.equals(existing.getAppKey(), identity.appKey());
            String appSecret = normalizeLength(request.getAppSecret(), 1024, "appSecret");
            String verificationToken = normalizeLength(
                    request.getVerificationToken(), 512, "verificationToken");
            String encryptKey = normalizeLength(request.getEncryptKey(), 512, "encryptKey");
            if (newApplication) {
                // A new application never inherits the previous application's credentials.
                requireNewApplicationCredentials(normalized, appSecret, verificationToken);
            }
            // A new application never needs the old bundle: an unreadable old secret must not block
            // replacing the identity, because the request already carries the new credentials.
            ImGroupChatCredentials currentCredentials = existing == null || newApplication
                    ? null : decryptGroupChatCredentials(existing);
            boolean credentialsChanged = appSecret != null || verificationToken != null
                    || encryptKey != null || request.isClearEncryptKey();
            ImGroupChatCredentials merged = newApplication
                    ? new ImGroupChatCredentials(appSecret,
                            "FEISHU".equals(normalized) ? verificationToken : null,
                            "FEISHU".equals(normalized) ? encryptKey : null)
                    : mergeCredentials(currentCredentials, normalized,
                            appSecret, verificationToken, encryptKey, request.isClearEncryptKey());
            String credentialRef = (credentialsChanged || newApplication) && !merged.isEmpty()
                    ? normalizeGroupCredentialRef(secretCrypto.encrypt(merged.toJson(objectMapper)))
                    : null;

            boolean enabled = request.isEnabled();
            PlatformImChannelConfigDO next = new PlatformImChannelConfigDO();
            next.setProvider(normalized);
            next.setPurpose(ImChannelPurpose.GROUP_CHAT.name());
            next.setEnabled(enabled ? 1 : 0);
            next.setRouteVersion(groupChatRouteVersion(existing, enabled,
                    identity.appKey(), identity.robotCode()));
            next.setAppKey(identity.appKey());
            next.setRobotCode(identity.robotCode());
            next.setCredentialRef(credentialRef);
            next.setCreatorId(userId);
            next.setModifierId(userId);
            configDao.upsert(next);
            PlatformImChannelConfigDO saved = configDao.findByProviderAndPurpose(
                    normalized, ImChannelPurpose.GROUP_CHAT.name());
            PlatformImChannelConfigDO effective = saved == null ? next : saved;
            if (effective.getEnabled() == 1 && !isGroupChatReadyConfig(effective)) {
                throw new BizException(ErrorCode.IM_CHANNEL_NOT_READY);
            }
            log.info("IM group-chat platform config updated provider={} enabled={} operatorId={}",
                    normalized, effective.getEnabled() == 1, userId);
            GroupChatConfigVO vo = toGroupChatVO(normalized, effective);
            vo.setSelected(normalized.equals(selectedProvider()));
            ImSelectionDO selection = configDao.selection();
            vo.setSelectionGeneration(selection == null || selection.getGeneration() == null
                    ? 0 : selection.getGeneration());
            return vo;
        } catch (AlreadyLoggedException e) {
            throw e;
        } catch (BizException e) {
            if (isExpected(e)) {
                throw e;
            }
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM group-chat platform config update failed provider={} operatorId={}",
                    normalized, userId, safe);
            throw safe;
        } catch (RuntimeException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM group-chat platform config update failed provider={} operatorId={}",
                    normalized, userId, safe);
            throw safe;
        }
    }

    public PlatformImChannelConfigDO findEnabled(String provider) {
        String normalizedProvider = ImProviderType.normalize(provider);
        try {
            if (!selectedProvider().equals(normalizedProvider)) return null;
            PlatformImChannelConfigDO config = configDao.findByProviderAndPurpose(
                    normalizedProvider, ImChannelPurpose.NOTIFY.name());
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

    public PlatformImChannelConfigDO findGroupChat(String provider) {
        return configDao.findByProviderAndPurpose(
                ImProviderType.normalize(provider), ImChannelPurpose.GROUP_CHAT.name());
    }

    public PlatformImChannelConfigDO findGroupChatById(Long id) {
        if (id == null) {
            return null;
        }
        PlatformImChannelConfigDO config = configDao.findById(id);
        return config != null && ImChannelPurpose.GROUP_CHAT.name().equals(config.getPurpose())
                ? config : null;
    }

    /**
     * Locking read for the delivery window: the sender keeps this row locked until the external
     * call ends, so a concurrent identity change waits (bounded) instead of splitting the send.
     */
    public PlatformImChannelConfigDO lockGroupChatForDelivery(String provider) {
        return configDao.lockGroupChatConfig(ImProviderType.normalize(provider),
                ImChannelPurpose.GROUP_CHAT.name());
    }

    public boolean isGroupChatEnabled(String provider) {
        PlatformImChannelConfigDO config = findGroupChat(provider);
        return config != null && Integer.valueOf(1).equals(config.getEnabled());
    }

    public boolean isGroupChatReady(String provider) {
        PlatformImChannelConfigDO config = findGroupChat(provider);
        return config != null && Integer.valueOf(1).equals(config.getEnabled())
                && isGroupChatReadyConfig(config);
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

    public ImGroupChatCredentials decryptGroupChatCredentials(PlatformImChannelConfigDO config) {
        if (config == null || !hasText(config.getCredentialRef())) {
            return null;
        }
        String provider = config.getProvider() == null ? "UNKNOWN" : config.getProvider();
        try {
            return ImGroupChatCredentials.parse(
                    objectMapper, secretCrypto.decrypt(config.getCredentialRef()));
        } catch (AlreadyLoggedException e) {
            throw e;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            AlreadyLoggedException safe = ImLogSupport.safeThrowable(e);
            log.error("IM group-chat platform config decrypt failed provider={}",
                    provider, safe);
            throw safe;
        }
    }

    public boolean isReady(String provider) {
        PlatformImChannelConfigDO config = findEnabled(provider);
        return config != null && isNotifyComplete(config);
    }

    private Identity resolveIdentity(String provider, UpdateGroupChatConfigRequest request,
                                     PlatformImChannelConfigDO existing) {
        String appKey;
        String robotCode;
        if ("FEISHU".equals(provider)) {
            appKey = normalizeLength(request.getAppId(), 128, "appId");
            if (appKey == null) {
                appKey = normalizeLength(request.getAppKey(), 128, "appId");
            }
            if (appKey != null && !FEISHU_APP_ID.matcher(appKey).matches()) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID,
                        "im.feishu_app_id_invalid", new Object[0],
                        "飞书 appId 格式不正确");
            }
            robotCode = null;
        } else {
            appKey = normalizeLength(request.getAppKey(), 128, "appKey");
            robotCode = normalizeLength(request.getRobotCode(), 128, "robotCode");
        }
        if (existing != null) {
            if (appKey == null) {
                appKey = existing.getAppKey();
            }
            if (robotCode == null && "DINGTALK".equals(provider)) {
                robotCode = existing.getRobotCode();
            }
        }
        return new Identity(appKey, robotCode);
    }

    /** True when the stored application identity changes; the caller must treat it as a new app. */
    private static boolean identityDiffers(PlatformImChannelConfigDO existing, Identity identity) {
        if (existing == null) {
            return false;
        }
        return !Objects.equals(existing.getAppKey(), identity.appKey())
                || !Objects.equals(existing.getRobotCode(), identity.robotCode());
    }

    private static void requireNewApplicationCredentials(String provider, String appSecret,
                                                         String verificationToken) {
        if (!hasText(appSecret)) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID,
                    "im.group_chat_new_app_secret_required", new Object[0],
                    "应用身份已变更，请填写新应用的 AppSecret");
        }
        if ("FEISHU".equals(provider) && !hasText(verificationToken)) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID,
                    "im.group_chat_new_app_token_required", new Object[0],
                    "飞书应用身份已变更，请填写新应用的 Verification Token");
        }
    }

    private void rejectNotifyIdentityConflict(String provider, Identity identity,
                                              PlatformImChannelConfigDO existingGroupChat) {
        PlatformImChannelConfigDO notifyConfig = configDao.findByProviderAndPurpose(
                provider, ImChannelPurpose.NOTIFY.name());
        if (notifyConfig == null) {
            return;
        }
        boolean sameAppKey = sameNewIdentity(
                existingGroupChat == null ? null : existingGroupChat.getAppKey(),
                identity.appKey(), notifyConfig.getAppKey());
        boolean sameRobotCode = sameNewIdentity(
                existingGroupChat == null ? null : existingGroupChat.getRobotCode(),
                identity.robotCode(), notifyConfig.getRobotCode());
        if (sameAppKey || sameRobotCode) {
            throw new ImIdentityConflictException(
                    ImIdentityConflictReason.NOTIFY_GROUP_IDENTITY_CONFLICT,
                    java.util.List.of());
        }
    }

    private void rejectSharedIdentityConflict(String provider, String appKey, String robotCode,
                                              PlatformImChannelConfigDO existingNotify) {
        PlatformImChannelConfigDO groupConfig = configDao.findByProviderAndPurpose(
                provider, ImChannelPurpose.GROUP_CHAT.name());
        if (groupConfig == null) {
            return;
        }
        boolean sameAppKey = sameNewIdentity(
                existingNotify == null ? null : existingNotify.getAppKey(),
                appKey, groupConfig.getAppKey());
        boolean sameRobotCode = sameNewIdentity(
                existingNotify == null ? null : existingNotify.getRobotCode(),
                robotCode, groupConfig.getRobotCode());
        if (sameAppKey || sameRobotCode) {
            throw new ImIdentityConflictException(
                    ImIdentityConflictReason.NOTIFY_GROUP_IDENTITY_CONFLICT,
                    java.util.List.of());
        }
    }

    private static boolean sameNewIdentity(String previous, String incoming, String other) {
        // A brand-new identity (no previous value) must not duplicate the other purpose; an
        // unchanged legacy identity is exempt so historical rows remain editable.
        return hasText(incoming) && hasText(other)
                && incoming.equals(other)
                && (!hasText(previous) || !incoming.equals(previous));
    }

    private ImGroupChatCredentials mergeCredentials(ImGroupChatCredentials current,
                                                    String provider, String appSecret,
                                                    String verificationToken, String encryptKey,
                                                    boolean clearEncryptKey) {
        ImGroupChatCredentials base = current == null
                ? new ImGroupChatCredentials(null, null, null) : current;
        if ("DINGTALK".equals(provider)) {
            return base.merge(appSecret, null, null, true);
        }
        return base.merge(appSecret, verificationToken, encryptKey, clearEncryptKey);
    }

    private ImGroupChatCredentials decryptGroupChatCredentialsQuietly(
            PlatformImChannelConfigDO config) {
        try {
            return decryptGroupChatCredentials(config);
        } catch (RuntimeException e) {
            log.warn("IM group-chat credentials could not be decoded for merge provider={}",
                    config.getProvider());
            return null;
        }
    }

    private static long nextRouteVersion(PlatformImChannelConfigDO existing, boolean enabled,
                                         String appKey, String robotCode) {
        long current = currentRouteVersion(existing);
        if (existing == null) {
            return current;
        }
        boolean enabledChanged = !Integer.valueOf(enabled ? 1 : 0).equals(existing.getEnabled());
        boolean identityChanged = !Objects.equals(existing.getAppKey(), appKey)
                || !Objects.equals(existing.getRobotCode(), robotCode);
        return enabledChanged || identityChanged ? current + 1 : current;
    }

    private static long groupChatRouteVersion(PlatformImChannelConfigDO existing,
                                              boolean enabled, String appKey, String robotCode) {
        long current = currentRouteVersion(existing);
        if (existing == null) {
            return current;
        }
        boolean enabledChanged = !Integer.valueOf(enabled ? 1 : 0).equals(existing.getEnabled());
        boolean identityChanged = !Objects.equals(existing.getAppKey(), appKey)
                || !Objects.equals(existing.getRobotCode(), robotCode);
        return enabledChanged || identityChanged ? current + 1 : current;
    }

    private static long currentRouteVersion(PlatformImChannelConfigDO config) {
        return config == null || config.getRouteVersion() == null ? 0 : config.getRouteVersion();
    }

    private static boolean isNotifyComplete(PlatformImChannelConfigDO config) {
        return hasText(config.getAppKey())
                && hasText(config.getCredentialRef())
                && ("FEISHU".equals(config.getProvider()) || hasText(config.getRobotCode()));
    }

    private static boolean isGroupChatComplete(PlatformImChannelConfigDO config) {
        return hasText(config.getAppKey())
                && hasText(config.getCredentialRef())
                && ("FEISHU".equals(config.getProvider()) || hasText(config.getRobotCode()));
    }

    private boolean isGroupChatReadyConfig(PlatformImChannelConfigDO config) {
        if (!isGroupChatComplete(config)) {
            return false;
        }
        ImGroupChatCredentials credentials = decryptGroupChatCredentials(config);
        if (credentials == null || !hasText(credentials.appSecret())) {
            return false;
        }
        return !"FEISHU".equals(config.getProvider())
                || hasText(credentials.verificationToken());
    }

    private static PlatformImChannelConfigVO toVO(PlatformImChannelConfigDO config) {
        PlatformImChannelConfigVO vo = new PlatformImChannelConfigVO();
        vo.setProvider(config.getProvider());
        vo.setPurpose(ImChannelPurpose.NOTIFY.name());
        vo.setRouteVersion(currentRouteVersion(config));
        vo.setVersion(config.getVersion());
        vo.setEnabled(Integer.valueOf(1).equals(config.getEnabled()));
        vo.setAppKey(config.getAppKey());
        vo.setRobotCode(config.getRobotCode());
        vo.setBaseUrl(config.getBaseUrl());
        vo.setSecretConfigured(hasText(config.getCredentialRef()));
        vo.setReady(vo.isEnabled() && isNotifyComplete(config));
        return vo;
    }

    private GroupChatConfigVO toGroupChatVO(String provider, PlatformImChannelConfigDO config) {
        GroupChatConfigVO vo = new GroupChatConfigVO();
        vo.setProvider(provider);
        if (config == null) {
            return vo;
        }
        vo.setConfigured(true);
        vo.setDesiredEnabled(Integer.valueOf(1).equals(config.getEnabled()));
        vo.setReady(vo.isDesiredEnabled() && isGroupChatComplete(config));
        vo.setAppKey(config.getAppKey());
        vo.setRobotCode(config.getRobotCode());
        vo.setSecretConfigured(hasText(config.getCredentialRef()));
        vo.setVersion(config.getVersion());
        vo.setRouteVersion(currentRouteVersion(config));
        ImGroupChatCredentials credentials = decryptGroupChatCredentialsQuietly(config);
        if (credentials != null) {
            vo.setVerificationTokenConfigured(hasText(credentials.verificationToken()));
            vo.setEncryptKeyConfigured(hasText(credentials.encryptKey()));
        }
        return vo;
    }

    private static PlatformImSelectionVO toSelectionVO(ImSelectionDO row) {
        PlatformImSelectionVO vo = new PlatformImSelectionVO();
        vo.setProvider(row.getProvider() == null ? null
                : ImProviderType.normalize(row.getProvider()));
        vo.setGeneration(row.getGeneration() == null ? 0 : row.getGeneration());
        vo.setActivatedAt(row.getActivatedAt());
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
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "im.field_too_long", new Object[]{field, maxLength}, field + " 长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private static String normalizeCredentialRef(String reference) {
        if (reference != null && reference.length() > 1024) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID,
                    "im.credential_reference_too_long", new Object[0], "加密凭据引用过长");
        }
        return reference;
    }

    private static String normalizeGroupCredentialRef(String reference) {
        if (reference != null && reference.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65535) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID,
                    "im.credential_reference_too_long", new Object[0], "加密凭据引用过长");
        }
        return reference;
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
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "im.base_url_https_required", new Object[0], "baseUrl 必须是绝对 HTTPS URL");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isExpected(BizException error) {
        return ErrorCode.PARAM_INVALID.getCode().equals(error.getCode())
                || ErrorCode.NO_PERMISSION.getCode().equals(error.getCode())
                || ErrorCode.CONFLICT.getCode().equals(error.getCode())
                || ErrorCode.IM_CHANNEL_NOT_READY.getCode().equals(error.getCode());
    }

    private record Identity(String appKey, String robotCode) {
    }
}
