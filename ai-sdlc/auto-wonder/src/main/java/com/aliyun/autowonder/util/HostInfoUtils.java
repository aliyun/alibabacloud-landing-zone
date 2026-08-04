package com.aliyun.autowonder.util;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.status.StatusLogger;
import org.springframework.util.ResourceUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class HostInfoUtils {

    public final static String APP_GROUP_KEY = "appGroup";
    public final static String HOST_NAME_KEY = "hostName";
    public final static String IP_KEY = "ip";
    public final static String IDC_NAME_KEY = "idcName";
    public final static String APP_USE_TYPE_KEY = "appUseType";
    public final static String ABBREV_KEY = "abbrev";
    public final static String SERVICE_STATE_KEY = "serviceState";
    public final static String DEPLOY_REGION_ID = "deployRegionId";
    public final static String PROFILE_ENV = "profileEnv";

    private final static String DEFAULT_APP_GROUP = "auto-wonder-server-local";
    public final static String DEFAULT_IP = "127.0.0.1";
    private final static String DEFAULT_HOST_NAME = "localhost";
    private final static String DEFAULT_REGION_ID = "center";

    private static final boolean CONTAINER_ENV_FLAG = detectContainerEnvironment();

    private static StatusLogger logger = StatusLogger.getLogger();

    private static final Map<String, String> ENV_PROPERTIES;

    static {
        ENV_PROPERTIES = initEnvProperties();
    }

    public static Map<String, String> getHostInfo() {
        return ENV_PROPERTIES;
    }

    public static boolean isContainerEnv() {
        return CONTAINER_ENV_FLAG;
    }

    private static Map<String, String> initEnvProperties() {
        Map<String, String> envProperties = new HashMap<>();
        envProperties.put(APP_GROUP_KEY, DEFAULT_APP_GROUP);
        envProperties.put(IP_KEY, DEFAULT_IP);
        envProperties.put(HOST_NAME_KEY, DEFAULT_HOST_NAME);
        envProperties.put(DEPLOY_REGION_ID, DEFAULT_REGION_ID);
        envProperties.put(IDC_NAME_KEY, "local");
        envProperties.put(APP_USE_TYPE_KEY, "PUBLISH");
        envProperties.put(SERVICE_STATE_KEY, "working_online");

        String host = StringUtils.firstNonEmpty(System.getenv("HOSTNAME"), System.getenv("POD_NAME"), localHostName());
        String ip = StringUtils.firstNonEmpty(System.getenv("POD_IP"), System.getenv("REQUESTED_IP"), localIp());
        setHostinfo(envProperties, System.getenv("AUTOWONDER_APP_GROUP"), host, ip,
                System.getenv("AUTOWONDER_SITE"), System.getenv("AUTOWONDER_USE_TYPE"),
                "working_online", System.getenv("AUTOWONDER_REGION"), System.getenv("SPRING_PROFILES_ACTIVE"));
        logger.info("hostinfo: {}", envProperties);

        try {
            URL url = ResourceUtils.getURL("classpath:git.properties");
            Properties props = new Properties();
            URLConnection con = url.openConnection();
            ResourceUtils.useCachesIfNecessary(con);
            try (InputStream is = con.getInputStream()) {
                props.load(is);
            }
            String abbrev = props.getProperty("git.commit.id.abbrev", "unknown");
            envProperties.put(ABBREV_KEY, abbrev);
        } catch (IOException e) {
            logger.warn("load git properties error.", e);
            envProperties.put(ABBREV_KEY, "unknown");
        }
        return Collections.unmodifiableMap(envProperties);
    }

    private static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static String localIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean detectContainerEnvironment() {
        return System.getenv("KUBERNETES_SERVICE_HOST") != null
                || System.getenv("CONTAINER") != null
                || Files.exists(Path.of("/.dockerenv"))
                || Files.exists(Path.of("/run/.containerenv"));
    }

    private static void setHostinfo(Map<String, String> envProperties, String appGroup, String hostName, String ip,
                                    String idcName, String useType, String serviceState, String deployRegionId, String profileEnv) {
        if (appGroup != null) {
            envProperties.put(APP_GROUP_KEY, appGroup);
        }
        if (ip != null) {
            envProperties.put(IP_KEY, ip);
        }
        if (hostName != null) {
            envProperties.put(HOST_NAME_KEY, hostName);
        }
        if (idcName != null) {
            envProperties.put(IDC_NAME_KEY, idcName);
        }
        if (useType != null) {
            envProperties.put(APP_USE_TYPE_KEY, useType);
        }
        if (serviceState != null) {
            envProperties.put(SERVICE_STATE_KEY, serviceState);
        }
        if (deployRegionId != null) {
            envProperties.put(DEPLOY_REGION_ID, deployRegionId);
        }
        if (profileEnv != null) {
            envProperties.put(PROFILE_ENV, profileEnv);
        }
    }

    public static boolean isLocalHost() {
        return "127.0.0.1".equals(ENV_PROPERTIES.get(HOST_NAME_KEY)) || "localhost".equals(ENV_PROPERTIES.get(HOST_NAME_KEY));
    }
}
