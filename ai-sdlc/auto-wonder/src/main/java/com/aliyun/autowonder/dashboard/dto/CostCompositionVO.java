package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class CostCompositionVO {
    private int range;
    private String scope;
    private BigDecimal total;
    private List<Source> sources;

    @Getter
    @Setter
    public static class Source {
        private String key;
        private String name;
        private BigDecimal credits;
        private BigDecimal share;
    }
}
