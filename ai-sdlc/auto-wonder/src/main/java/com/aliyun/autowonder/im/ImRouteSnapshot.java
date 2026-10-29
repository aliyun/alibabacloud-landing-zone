package com.aliyun.autowonder.im;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Immutable routing decision captured when a message is durably received. Replies are only
 * delivered while the binding and platform config still match this snapshot. Contains no secrets.
 */
public record ImRouteSnapshot(String provider, Long bindingId, String bindingMode,
        Long platformConfigId, long bindingRouteVersion, long configRouteVersion,
        Long tenantId, Long agentId, String groupId, long selectionGeneration, long acceptedAt) {

    public static final String MODE_LEGACY = "LEGACY";
    public static final String MODE_SHARED = "SHARED";

    public static ImRouteSnapshot legacy(String provider, Long bindingId, long bindingRouteVersion,
            Long tenantId, Long agentId, long selectionGeneration, long acceptedAt) {
        return new ImRouteSnapshot(provider, bindingId, MODE_LEGACY, null, bindingRouteVersion, 0,
                tenantId, agentId, null, selectionGeneration, acceptedAt);
    }

    public static ImRouteSnapshot shared(String provider, Long bindingId, Long platformConfigId,
            long bindingRouteVersion, long configRouteVersion, Long tenantId, Long agentId,
            String groupId, long selectionGeneration, long acceptedAt) {
        return new ImRouteSnapshot(provider, bindingId, MODE_SHARED, platformConfigId,
                bindingRouteVersion, configRouteVersion, tenantId, agentId, groupId,
                selectionGeneration, acceptedAt);
    }

    public boolean shared() {
        return MODE_SHARED.equals(bindingMode);
    }

    public boolean legacy() {
        return MODE_LEGACY.equals(bindingMode);
    }

    public boolean valid() {
        if (provider == null || bindingId == null || tenantId == null || agentId == null) {
            return false;
        }
        if (shared()) {
            return platformConfigId != null && groupId != null && !groupId.isBlank();
        }
        return legacy() && platformConfigId == null;
    }

    public String toJson(ObjectMapper json) {
        try {
            return json.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化路由快照", e);
        }
    }

    public static ImRouteSnapshot parse(ObjectMapper json, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            ImRouteSnapshot snapshot = json.readValue(value, ImRouteSnapshot.class);
            return snapshot != null && snapshot.valid() ? snapshot : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Short, non-sensitive description for audit and diagnostics. */
    public String describe() {
        return "provider=" + provider + " bindingId=" + bindingId + " mode=" + bindingMode
                + " configId=" + platformConfigId + " bindingRouteVersion=" + bindingRouteVersion
                + " configRouteVersion=" + configRouteVersion + " group="
                + (groupId == null ? "-" : Integer.toHexString(Objects.hashCode(groupId)));
    }
}
