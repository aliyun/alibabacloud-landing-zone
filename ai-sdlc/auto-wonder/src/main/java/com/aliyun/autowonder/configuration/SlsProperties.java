package com.aliyun.autowonder.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "autowonder.sls")
public class SlsProperties {

    private boolean enabled;
    private String endpoint;
    private String project;
    private String sysLogStore;
    private String bizLogStore;
    private String metricLogStore;
    private String accessKeyId;
    private String accessKeySecret;
    private String topic = "";
    private String source = "";

    public void validate() {
        if (!enabled) {
            return;
        }
        require(endpoint, "endpoint");
        require(project, "project");
        require(sysLogStore, "sys-log-store");
        require(bizLogStore, "biz-log-store");
        require(metricLogStore, "metric-log-store");
        require(accessKeyId, "access-key-id");
        require(accessKeySecret, "access-key-secret");
    }

    private static void require(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("autowonder.sls." + name + " is required when SLS is enabled");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }
    public String getSysLogStore() { return sysLogStore; }
    public void setSysLogStore(String sysLogStore) { this.sysLogStore = sysLogStore; }
    public String getBizLogStore() { return bizLogStore; }
    public void setBizLogStore(String bizLogStore) { this.bizLogStore = bizLogStore; }
    public String getMetricLogStore() { return metricLogStore; }
    public void setMetricLogStore(String metricLogStore) { this.metricLogStore = metricLogStore; }
    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
    public String getAccessKeySecret() { return accessKeySecret; }
    public void setAccessKeySecret(String accessKeySecret) { this.accessKeySecret = accessKeySecret; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
