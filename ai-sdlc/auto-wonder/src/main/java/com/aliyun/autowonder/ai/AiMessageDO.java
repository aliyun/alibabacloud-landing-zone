package com.aliyun.autowonder.ai;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class AiMessageDO {
    private Long id;
    private Long tenantId;
    private Long sessionId;
    private Integer seq;
    private String role;
    private String content;
    private String metaJson;
    private Date gmtCreate;
}
