package com.aliyun.autowonder.repo;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class RepoDO {
    private Long id;
    private Long tenantId;
    private String name;
    private String url;
    private String defaultBranch;
    private String description;
    private String scanStatus;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;

    private static final long SCAN_STALE_MILLIS = 10 * 60 * 1000L;

    public boolean isScanActive() {
        return "SCANNING".equals(scanStatus)
                && gmtModified != null
                && System.currentTimeMillis() - gmtModified.getTime() < SCAN_STALE_MILLIS;
    }
}
