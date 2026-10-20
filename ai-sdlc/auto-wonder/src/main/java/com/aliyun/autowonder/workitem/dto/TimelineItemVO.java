package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;
import java.util.List;

@Getter
@Setter
public class TimelineItemVO {
    private Long id;
    private String type;
    private Long authorId;
    private String authorName;
    private String authorType;
    private boolean isAgent;
    private String content;
    private String eventType;
    private String fromValDisplay;
    private String toValDisplay;
    private String operatorDisplayName;
    private Date gmtCreate;
    /** Source workitem metadata used when rendering an external-sync timeline event. */
    private String sourceProvider;
    private String sourceExternalWorkitemId;
    private String sourceExternalUrl;
    private List<CommentInteractionVO> interactions;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private ExternalPrincipalVO authorNameFallback;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private ExternalPrincipalVO fromValNameFallback;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private ExternalPrincipalVO toValNameFallback;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private ExternalPrincipalVO operatorNameFallback;
}
