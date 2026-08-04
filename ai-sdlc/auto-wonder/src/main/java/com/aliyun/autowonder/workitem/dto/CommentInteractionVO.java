package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class CommentInteractionVO {
    private Long guidanceId;
    private Long targetAgentId;
    private String targetAgentName;
    private String status;
    private String error;
    private Long replyCommentId;
    private String replyContent;
    private Date repliedAt;
}
