package com.aliyun.autowonder.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "oss")
@Getter
@Setter
public class OssProperties {
    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    /** default/fallback bucket */
    private String bucket;
    /** task-package zips */
    private String taskPkgBucket;
    /** artifacts */
    private String artifactBucket;
    /** user uploaded skill packages */
    private String skillBucket;

    public String resolveTaskPkgBucket() {
        return resolve(taskPkgBucket);
    }

    public String resolveArtifactBucket() {
        return resolve(artifactBucket);
    }

    public String resolveSkillBucket() {
        return resolve(skillBucket);
    }

    private String resolve(String workloadBucket) {
        return StringUtils.hasText(workloadBucket) ? workloadBucket : bucket;
    }
}
