package com.aliyun.autowonder.im;

/** Why a robot identity cannot be saved; the frontend renders a distinct message per reason. */
public enum ImIdentityConflictReason {
    /** A legacy standalone binding in another Workspace already uses this identity. */
    LEGACY_BINDING_OCCUPIED,
    /** The notification bot and the shared group-chat bot must not share an application identity. */
    NOTIFY_GROUP_IDENTITY_CONFLICT,
    /** A legacy binding tried to reuse the platform shared group-chat application identity. */
    SHARED_IDENTITY_OCCUPIED
}
