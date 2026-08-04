package com.aliyun.autowonder.dispatch.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DispatchPageVO {
    private List<DispatchVO> list;
    private long total;
    private int page;
    private int pageSize;

    public DispatchPageVO() {}

    public DispatchPageVO(List<DispatchVO> list, long total, int page, int pageSize) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }
}
