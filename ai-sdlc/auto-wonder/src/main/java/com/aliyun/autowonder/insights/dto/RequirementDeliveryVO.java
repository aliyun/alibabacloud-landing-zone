package com.aliyun.autowonder.insights.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 需求交付吞吐、交付时长与每周目标完成比例。统计口径见 {@code RequirementDeliveryService}。 */
@Getter
@Setter
public class RequirementDeliveryVO {
    private String startDate;
    private String endDate;
    private String timezone;
    private String dataThrough;
    /** 用户配置的处理时长目标（小时）；未配置为 null，不预填默认目标。 */
    private Long targetHours;
    private Summary summary;
    private List<Week> weeks;
    private List<Sample> samples;

    @Getter
    @Setter
    public static class DurationStats {
        private Long averageDurationSeconds;
        private Long medianDurationSeconds;
        private int validSampleCount;
        private int missingSampleCount;
        /** 达标样本数；未配置目标为 null。 */
        private Integer metTargetCount;
        /** 达标样本数 / 可判定时长的样本数 × 100，一位小数；未配置目标或无有效样本为 null。 */
        private Double goalRatio;
    }

    @Getter
    @Setter
    public static class Summary extends DurationStats {
        /** 区间内已交付的独立需求数（按 workitem 去重）。 */
        private long deliveredCount;
        /** 紧邻的前一个等时长区间。 */
        private Period previous;
        /** (本期 - 上期) / 上期 × 100；上期为 0 表示暂无可比基数，返回 null。 */
        private Double changePercent;
    }

    @Getter
    @Setter
    public static class Period {
        private String startDate;
        private String endDate;
        private long deliveredCount;
    }

    @Getter
    @Setter
    public static class Week extends DurationStats {
        /** ISO 周（周一）完整周区间。 */
        private String weekStart;
        private String weekEnd;
        /** 该周落在所选时间范围内的实际覆盖区间；与完整周不一致时 partial 为 true。 */
        private String coveredFrom;
        private String coveredTo;
        private boolean partial;
        private long deliveredCount;
    }

    @Getter
    @Setter
    public static class Sample {
        private long workitemId;
        private String title;
        private String deliveredAt;
        private String deliveryStartedAt;
        private Long durationSeconds;
        /** 未配置目标或时长缺失时为 null，不把无法判定的样本算成未达标。 */
        private Boolean metTarget;
        /** 时长缺失原因：MISSING_DELIVERY_START / INVALID_TIMELINE；有效样本为 null。 */
        private String missingReason;
    }
}
