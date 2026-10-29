package com.aliyun.autowonder.im;

import lombok.Data;

import java.util.Date;

@Data
public class ImSelectionDO {
    private Integer id;
    private String provider;
    private Long generation;
    private Date activatedAt;
}
