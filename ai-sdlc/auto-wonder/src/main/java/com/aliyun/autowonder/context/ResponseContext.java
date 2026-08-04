package com.aliyun.autowonder.context;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResponseContext {

    private boolean success;

    private String errorCode;

    private String errorMsg;

}
