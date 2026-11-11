package com.aliyun.autowonder.prd.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Saves a design draft. {@code designJson} is the structured design data and is the only content
 * source: the HTML is rendered from it server-side. When {@code versionId} is set the named draft is
 * rewritten, otherwise a new version is appended.
 */
@Getter
@Setter
public class SaveDesignRequest {
    private Long versionId;
    /** Required when rewriting an existing draft, from the version the author read. */
    private Integer expectedVersion;
    private String designJson;
    private String changeSummary;
    private Long aiSessionId;
}
