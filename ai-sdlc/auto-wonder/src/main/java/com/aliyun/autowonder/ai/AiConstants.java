package com.aliyun.autowonder.ai;

public final class AiConstants {

    public static final class Scene {
        public static final String REPO_SCAN = "REPO_SCAN";
        public static final String MEMORY_IMPORT = "MEMORY_IMPORT";
        public static final String SDLC_GEN = "SDLC_GEN";
        public static final String AGENT_CONFIG_GEN = "AGENT_CONFIG_GEN";
        public static final String CLARIFICATION = "CLARIFICATION";
        private Scene() {}
    }

    public static final class Status {
        public static final String QUEUED = "QUEUED";
        public static final String RUNNING = "RUNNING";
        public static final String WAIT_USER = "WAIT_USER";
        public static final String COMPLETED = "COMPLETED";
        public static final String FAILED = "FAILED";
        public static final String CANCELED = "CANCELED";
        private Status() {}
    }

    public static final class Role {
        public static final String USER = "USER";
        public static final String AI = "AI";
        public static final String SYSTEM = "SYSTEM";
        private Role() {}
    }

    private AiConstants() {}
}
