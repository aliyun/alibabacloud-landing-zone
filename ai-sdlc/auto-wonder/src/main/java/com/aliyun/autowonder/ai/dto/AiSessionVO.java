package com.aliyun.autowonder.ai.dto;

import com.aliyun.autowonder.ai.AiMessageDO;
import lombok.Getter;
import lombok.Setter;
import java.util.Date;
import java.util.List;

@Getter
@Setter
public class AiSessionVO {
    private Long id;
    private String scene;
    private String bizRefType;
    private Long bizRefId;
    private String status;
    private String resultJson;
    private String error;
    private Date gmtCreate;
    private List<AiMessageDO> messages;
}
