package com.aliyun.autowonder.insights.dto;

import java.util.List;

public class HumanAgentSlowTailPageVO {

    private int tailSize;
    private int total;
    private int page;
    private int pageSize;
    private List<HumanAgentParticipationVO.P90Workitem> items;

    public int getTailSize() { return tailSize; }
    public void setTailSize(int v) { this.tailSize = v; }
    public int getTotal() { return total; }
    public void setTotal(int v) { this.total = v; }
    public int getPage() { return page; }
    public void setPage(int v) { this.page = v; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int v) { this.pageSize = v; }
    public List<HumanAgentParticipationVO.P90Workitem> getItems() { return items; }
    public void setItems(List<HumanAgentParticipationVO.P90Workitem> v) { this.items = v; }
}
