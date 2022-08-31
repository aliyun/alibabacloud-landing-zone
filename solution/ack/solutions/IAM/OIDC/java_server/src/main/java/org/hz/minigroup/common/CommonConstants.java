package org.hz.minigroup.common;


import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Properties;

public class CommonConstants {
    private static final Logger logger = LoggerFactory.getLogger(CommonConstants.class);
    public static final String TOKENS_PATH;
    public static final String OIDC_PRODIVER_ARN;
    public static final String ROLE_ARN;
    public static final String OIDC_TOKEN;
    public static final String ROLE_SESSION_NAME;
    public static final String STS_REGION;
    public static final String STS_AK;
    public static final String STS_SK;
    public static final String STS_ENDPOINT;


    static {
        TOKENS_PATH = "/var/run/secrets/tokens/oidc-token";
    }

    static {
        OIDC_TOKEN = loadOidcToken();
    }

    static {
        Properties properties = loadProperties();
        STS_AK = properties.getProperty("oidc.sts.ak");
        STS_SK = properties.getProperty("oidc.sts.sk");
        OIDC_PRODIVER_ARN =  properties.getProperty("oidc.sts.provider_arn");
        ROLE_ARN = properties.getProperty("oidc.sts.role_arn");
        ROLE_SESSION_NAME = properties.getProperty("oidc.sts.role_session_name");
        STS_REGION = properties.getProperty("oidc.sts.region");
        STS_ENDPOINT = properties.getProperty("oidc.sts.endpoint");
    }

    public static String loadOidcToken() {
        InputStream ins = null;
        try {
            File file = null;
            String configPath = TOKENS_PATH;
            if (StringUtils.isNotBlank(configPath)) {
                file = new File(configPath);
            }
            if (StringUtils.isBlank(configPath) || !file.exists()) {
                logger.info("[1]can not find token file[-D]:" + configPath);
                System.exit(0);
            }
            ins = new FileInputStream(file);
            String myString = IOUtils.toString(ins, "utf-8");
            return myString;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            System.exit(0);
        } finally {
            try {
                ins.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return "";
    }

    public static Properties loadProperties() {

        Properties properties = new Properties();
        try {
            File file = null;

            String configPath = new Object() {
                public String getPath() {
                    return this.getClass().getResource("/oidc.properties").getPath();
                }
            }.getPath();

            if (StringUtils.isNotBlank(configPath)) {
                file = new File(configPath);
            }
            if (StringUtils.isBlank(configPath) || !file.exists()) {
                logger.info("[1]can not find config file[-D]:" + configPath);
                throw new RuntimeException("can not find config file!");
            }

            InputStream ins = new FileInputStream(file);
            InputStreamReader reader = new InputStreamReader(ins, "UTF-8");
            properties.load(reader);
            ins.close();
            reader.close();
            logger.info("load config file:" + file.getAbsolutePath());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            System.exit(0);
        }
        return properties;
    }
}

