package com.aliyun.autowonder.insights.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 交付总览近期统计：新增工单（含按日趋势）与交付吞吐（含环比）。
 * 完成口径与 {@code RequirementDeliveryService} 一致：当前处于 DONE 类状态节点、
 * 完成时间取最近一次进入 DONE 类节点的状态事件，按 workitem 去重，取消不计。
 */
@Getter
@Setter
public class DeliveryOverviewVO {
    private String startDate;
    private String endDate;
    private String timezone;
    /** 统计可视数据截止日（Asia/Shanghai 当天），不晚于 endDate。 */
    private String dataThrough;
    /** 区间天数（含首尾），用于日均计算与界面标注。 */
    private int daysInRange;
    private Created created;
    private Delivered delivered;

    @Getter
    @Setter
    public static class Created {
        private long count;
        /** 区间内逐日创建数（Asia/Shanghai 自然日），无数据的日期零填充。 */
        private List<DailyCount> daily;
    }

    @Getter
    @Setter
    public static class DailyCount {
        private String date;
        private long count;
    }

    @Getter
    @Setter
    public static class Delivered {
        private long count;
        /** 完成数 / 区间天数，一位小数。 */
        private Double dailyAverage;
        /** 紧邻的前一个等时长区间。 */
        private Period previous;
        /** (本期 - 上期) / 上期 × 100，一位小数；上期为 0 时无可比基数，返回 null。 */
        private Double changePercent;
    }

    @Getter
    @Setter
    public static class Period {
        private String startDate;
        private String endDate;
        private long count;
    }
}
