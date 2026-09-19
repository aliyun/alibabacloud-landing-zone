package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;
import java.util.List;

@Getter
@Setter
public class ExecutorVO {
    private Long id;
    private Long agentId;
    private String agentName;
    private String name;
    private String status;       // reflects live online status
    private String clientKind;
    private String lastConnectIp;
    private Date lastHeartbeat;
    private Date lastStartedAt;
    /**
     * Daemon-reported client-runtime version from live heartbeat presence (Redis). Null when the
     * client never reported one, or when it has been quiet long enough for the presence key to
     * expire — an offline executor is no longer judgeable by version.
     */
    private String version;
    /** Daemon-reported effective model id from heartbeat presence (Redis); null when never reported. */
    private String model;
    /** Display name of {@link #model} resolved from the provider model catalog; null when unknown. */
    private String modelName;
    private Date gmtCreate;
    private boolean restartSupported;
    private boolean updateRestartSupported;
    private com.alibaba.fastjson.JSONObject restart;
    /** Whether the connected client understands the {@code EXECUTOR_UPGRADE} command. */
    private boolean upgradeSupported;
    /** Whether {@link #version} is a parseable stable version strictly below {@link #targetVersion}. */
    private boolean upgradeAvailable;
    /**
     * Whether {@link #version} and {@link #targetVersion} could be compared at all. {@code upgradeAvailable}
     * is false both when the executor already runs the target and when its version is unreadable
     * ({@code 0.2.155-beta.1}, {@code dev}, never reported); only the first means 无需升级, because the
     * second is still accepted by a manual upgrade.
     */
    private boolean versionComparable;
    /** Global runtime version every upgrade targets; there is no per-executor override. */
    private String targetVersion;
    /** Newest upgrade task, or null when this executor was never asked to upgrade. */
    private ExecutorUpdateVO update;
    /** Squads of the owning digital worker; empty when the worker is unaffiliated. */
    private List<Long> squadIds;
    private List<String> squadNames;
}
