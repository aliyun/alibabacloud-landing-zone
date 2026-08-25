package com.aliyun.autowonder.integration.aone;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "autowonder.integration.aone")
public class AoneIntegrationProperties {

    private boolean enabled;

    /**
     * Aone web console base URL, used only to build a deep link when an Aone API response
     * carries no webUrl/url. Deployment-specific and unset by default so that no
     * environment host is baked into the distribution.
     */
    private String webBaseUrl;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWebBaseUrl() {
        return webBaseUrl;
    }

    public void setWebBaseUrl(String webBaseUrl) {
        this.webBaseUrl = webBaseUrl;
    }

    public void requireEnabled() {
        if (!enabled) {
            throw new AoneDisabledException("Aone integration is disabled");
        }
    }
}
