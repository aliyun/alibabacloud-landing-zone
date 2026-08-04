package com.aliyun.autowonder.integration.provider;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PageResult<T> {
    private List<T> items;
    private int page;
    private int pageSize;
    private int totalCount;

    public static <T> PageResult<T> of(List<T> items, int page, int pageSize, int totalCount) {
        PageResult<T> result = new PageResult<>();
        result.setItems(items);
        result.setPage(page);
        result.setPageSize(pageSize);
        result.setTotalCount(totalCount);
        return result;
    }
}
