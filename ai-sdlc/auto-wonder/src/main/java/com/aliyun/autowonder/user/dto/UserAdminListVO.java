package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户管理分页结果。{@code totalAll} 是全部用户总数（含已删除），固定展示、不随筛选变化；
 * {@code total} 是当前筛选条件下的结果总数（分页器口径）。
 */
@Getter
@Setter
public class UserAdminListVO {
    private long totalAll;
    private long total;
    private int pageNum;
    private int pageSize;
    private List<UserAdminUserVO> list = new ArrayList<>();
}
