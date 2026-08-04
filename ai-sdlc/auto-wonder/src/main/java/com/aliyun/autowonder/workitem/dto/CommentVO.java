package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class CommentVO {
    private Long id;
    private Long workitemId;
    private String authorType;
    private Long authorRef;
    private String contentMd;
    private Date gmtCreate;
}
