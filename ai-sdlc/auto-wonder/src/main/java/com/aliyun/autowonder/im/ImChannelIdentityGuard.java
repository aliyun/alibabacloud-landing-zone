package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.integration.dingtalk.DingtalkRobotBindingDO;
import com.aliyun.autowonder.integration.dingtalk.DingtalkRobotBindingDao;
import com.aliyun.autowonder.integration.feishu.FeishuBinding;
import com.aliyun.autowonder.integration.feishu.FeishuBindingDao;
import org.springframework.stereotype.Component;

/**
 * Cross-table identity and group exclusivity. Callers hold the platform selection
 * row lock so these checks serialize with config and binding writes.
 */
@Component
public class ImChannelIdentityGuard {
    private final PlatformImChannelConfigDao configDao;
    private final DingtalkRobotBindingDao dingtalkBindings;
    private final FeishuBindingDao feishuBindings;

    public ImChannelIdentityGuard(PlatformImChannelConfigDao configDao,
            DingtalkRobotBindingDao dingtalkBindings, FeishuBindingDao feishuBindings) {
        this.configDao = configDao;
        this.dingtalkBindings = dingtalkBindings;
        this.feishuBindings = feishuBindings;
    }

    /** GROUP_CHAT identity must differ from historical NOTIFY and every LEGACY binding. */
    public void assertGroupChatIdentityAvailable(String provider, String appKey, String robotCode) {
        java.util.List<Long> occupants = new java.util.ArrayList<>();
        boolean occupied = false;
        if ("DINGTALK".equals(provider)) {
            if (hasText(appKey)) {
                DingtalkRobotBindingDO row = dingtalkBindings.findLegacyByAppKeyGlobal(appKey);
                occupied = occupied || row != null;
                addOccupant(occupants, row);
            }
            if (hasText(robotCode)) {
                DingtalkRobotBindingDO row = dingtalkBindings.findByRobotCodeGlobal(robotCode);
                occupied = occupied || row != null;
                addOccupant(occupants, row);
            }
        } else if ("FEISHU".equals(provider)) {
            if (hasText(appKey)) {
                FeishuBinding row = feishuBindings.findLegacyByAppIdGlobal(appKey);
                occupied = occupied || row != null;
                addOccupant(occupants, row);
            }
        }
        if (occupied) {
            throw new ImIdentityConflictException(
                    ImIdentityConflictReason.LEGACY_BINDING_OCCUPIED, occupants);
        }
    }

    /** A LEGACY binding must not reuse the shared group-chat application identity. */
    public void assertLegacyIdentityAvailable(String provider, String appKey, String robotCode) {
        PlatformImChannelConfigDO groupConfig = configDao.findByProviderAndPurpose(
                provider, ImChannelPurpose.GROUP_CHAT.name());
        if (groupConfig == null) {
            return;
        }
        if ("DINGTALK".equals(provider)) {
            if (matches(appKey, groupConfig.getAppKey()) || matches(robotCode, groupConfig.getRobotCode())) {
                throw new ImIdentityConflictException(
                        ImIdentityConflictReason.SHARED_IDENTITY_OCCUPIED, java.util.List.of());
            }
        } else if ("FEISHU".equals(provider) && matches(appKey, groupConfig.getAppKey())) {
            throw new ImIdentityConflictException(
                    ImIdentityConflictReason.SHARED_IDENTITY_OCCUPIED, java.util.List.of());
        }
    }

    /** A group can be bound to only one agent per channel, regardless of binding status. */
    public void assertGroupAvailable(String provider, Long platformConfigId, String groupId,
            Long excludeBindingId) {
        if (platformConfigId == null || !hasText(groupId)) {
            return;
        }
        String group = groupId.trim();
        Long occupiedBy;
        if ("DINGTALK".equals(provider)) {
            DingtalkRobotBindingDO row = dingtalkBindings.findSharedByConfigAndGroup(platformConfigId, group);
            occupiedBy = row == null ? null : row.getId();
        } else if ("FEISHU".equals(provider)) {
            FeishuBinding row = feishuBindings.findSharedByConfigAndGroup(platformConfigId, group);
            occupiedBy = row == null ? null : row.getId();
        } else {
            return;
        }
        if (occupiedBy != null && !occupiedBy.equals(excludeBindingId)) {
            throw BizException.keyed(ErrorCode.CONFLICT, "integration.group_already_bound",
                    new Object[0], "该群已绑定到数字人，请先解除原绑定");
        }
    }

    private static boolean matches(String value, String other) {
        return hasText(value) && hasText(other) && value.equals(other);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void addOccupant(java.util.List<Long> occupants, DingtalkRobotBindingDO row) {
        if (row != null && row.getTenantId() != null && !occupants.contains(row.getTenantId())) {
            occupants.add(row.getTenantId());
        }
    }

    private static void addOccupant(java.util.List<Long> occupants, FeishuBinding row) {
        if (row != null && row.getTenantId() != null && !occupants.contains(row.getTenantId())) {
            occupants.add(row.getTenantId());
        }
    }
}
