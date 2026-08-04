package com.aliyun.autowonder.integration.aone;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "autowonder.integration.aone")
public class AoneIntegrationProperties {

    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void requireEnabled() {
        if (!enabled) {
            throw new AoneDisabledException("Aone integration is disabled");
        }
    }
}
