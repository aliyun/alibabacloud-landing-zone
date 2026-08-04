package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IssuedExecutorVO {
    private Long id;
    private Long agentId;
    private String name;
    /** one-time plaintext token; not retrievable again */
    private String token;
}
