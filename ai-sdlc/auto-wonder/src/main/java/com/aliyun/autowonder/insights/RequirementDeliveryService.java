package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.common.error.KeyedIllegalArgumentException;
import com.aliyun.autowonder.insights.dto.RequirementDeliveryVO;
import com.aliyun.autowonder.user.UserSettingService;
import com.aliyun.autowonder.user.dto.UserSettingVO;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 需求交付吞吐、交付时长与每周目标完成比例（首期口径）。
 * <p>
 * 已交付 = 当前处于 DONE 类状态节点的 REQ，即项目自身的完成定义，不凭状态名称或 dispatch SUCCEEDED 推断；
 * 交付完成时间 = 最近一次进入 DONE 类节点的状态事件（重开后再交付取最近一次，按 workitem 去重）；
 * 交付开始时间 = 首次正式交付派发（最早 dispatch），缺失不编造并单列缺失样本；
 * 交付时长 = 完成 - 开始的端到端墙钟时长，包含等待、暂停、返工与重试。
 */
@Service
public class RequirementDeliveryService {

    /** 处理时长目标（整数小时）的用户级偏好键，值形如 JSON 数字 {@code 24}。 */
    public static final String TARGET_SETTING_KEY = "insights.requirementDelivery.targetHours";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_RANGE_DAYS = 366;
    private static final long MAX_TARGET_HOURS = 10_000L;

    private final RequirementDeliveryDao dao;
    private final UserSettingService userSettingService;

    public RequirementDeliveryService(RequirementDeliveryDao dao, UserSettingService userSettingService) {
        this.dao = dao;
        this.userSettingService = userSettingService;
    }

    /** 区间内已交付需求的取数行；名称与 mapping 的 resultType 对齐。 */
    @Getter
    @Setter
    public static class DeliveredRequirement {
        private Long workitemId;
        private String title;
        private Date deliveredAt;
        private Date deliveryStartedAt;
    }

    public RequirementDeliveryVO get(long tenantId, Long userId, LocalDate start, LocalDate end) {
        LocalDate today = LocalDate.now(ZONE);
        if (start == null || end == null || start.isAfter(end) || end.isAfter(today)
                || ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw new KeyedIllegalArgumentException("insights.invalid_date_range", null,
                    "Invalid requirement delivery range: " + start + ".." + end + " (today=" + today + ")");
        }
        Long targetHours = readTargetHours(userId);
        long spanDays = ChronoUnit.DAYS.between(start, end);
        LocalDate previousEnd = start.minusDays(1);
        LocalDate previousStart = previousEnd.minusDays(spanDays);

        List<DeliveredRequirement> current = dao.listDeliveredRequirements(
                tenantId, atStart(start), atStart(end.plusDays(1)));
        List<DeliveredRequirement> previous = dao.listDeliveredRequirements(
                tenantId, atStart(previousStart), atStart(start));

        LocalDate firstWeekStart = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<RequirementDeliveryVO.Week> weeks = weekSkeletons(start, end, firstWeekStart);
        List<List<RequirementDeliveryVO.Sample>> buckets = new ArrayList<>();
        for (int i = 0; i < weeks.size(); i++) {
            buckets.add(new ArrayList<>());
        }
        List<RequirementDeliveryVO.Sample> samples = new ArrayList<>();
        for (DeliveredRequirement row : current) {
            RequirementDeliveryVO.Sample sample = toSample(row, targetHours);
            samples.add(sample);
            LocalDate deliveredDate = row.getDeliveredAt().toInstant().atZone(ZONE).toLocalDate();
            int index = (int) (ChronoUnit.DAYS.between(firstWeekStart, deliveredDate) / 7);
            if (index >= 0 && index < buckets.size()) {
                buckets.get(index).add(sample);
            }
        }
        for (int i = 0; i < weeks.size(); i++) {
            RequirementDeliveryVO.Week week = weeks.get(i);
            week.setDeliveredCount(buckets.get(i).size());
            fillDurationStats(week, buckets.get(i), targetHours);
        }

        RequirementDeliveryVO vo = new RequirementDeliveryVO();
        vo.setStartDate(start.toString());
        vo.setEndDate(end.toString());
        vo.setTimezone(ZONE.getId());
        vo.setDataThrough(today.toString());
        vo.setTargetHours(targetHours);
        vo.setSummary(buildSummary(samples, previous.size(), previousStart, previousEnd, targetHours));
        vo.setWeeks(weeks);
        vo.setSamples(samples);
        return vo;
    }

    private RequirementDeliveryVO.Summary buildSummary(List<RequirementDeliveryVO.Sample> samples,
                                                       long previousCount, LocalDate previousStart,
                                                       LocalDate previousEnd, Long targetHours) {
        RequirementDeliveryVO.Summary summary = new RequirementDeliveryVO.Summary();
        summary.setDeliveredCount(samples.size());
        RequirementDeliveryVO.Period period = new RequirementDeliveryVO.Period();
        period.setStartDate(previousStart.toString());
        period.setEndDate(previousEnd.toString());
        period.setDeliveredCount(previousCount);
        summary.setPrevious(period);
        summary.setChangePercent(changePercent(samples.size(), previousCount));
        fillDurationStats(summary, samples, targetHours);
        return summary;
    }

    /** 首尾不完整周保留完整 ISO 周区间，同时给出范围内实际覆盖区间供界面标注。 */
    private static List<RequirementDeliveryVO.Week> weekSkeletons(LocalDate start, LocalDate end,
                                                                  LocalDate firstWeekStart) {
        List<RequirementDeliveryVO.Week> weeks = new ArrayList<>();
        for (LocalDate weekStart = firstWeekStart; !weekStart.isAfter(end); weekStart = weekStart.plusWeeks(1)) {
            LocalDate weekEnd = weekStart.plusDays(6);
            LocalDate coveredFrom = weekStart.isBefore(start) ? start : weekStart;
            LocalDate coveredTo = weekEnd.isAfter(end) ? end : weekEnd;
            RequirementDeliveryVO.Week week = new RequirementDeliveryVO.Week();
            week.setWeekStart(weekStart.toString());
            week.setWeekEnd(weekEnd.toString());
            week.setCoveredFrom(coveredFrom.toString());
            week.setCoveredTo(coveredTo.toString());
            week.setPartial(!coveredFrom.equals(weekStart) || !coveredTo.equals(weekEnd));
            weeks.add(week);
        }
        return weeks;
    }

    /** 仅把可判定时长的样本计入均值与目标分母，缺失样本单列，避免把无法判定的样本算成未达标。 */
    private static void fillDurationStats(RequirementDeliveryVO.DurationStats stats,
                                          List<RequirementDeliveryVO.Sample> samples, Long targetHours) {
        List<Long> durations = new ArrayList<>();
        int met = 0;
        for (RequirementDeliveryVO.Sample sample : samples) {
            if (sample.getDurationSeconds() != null) {
                durations.add(sample.getDurationSeconds());
            }
            if (Boolean.TRUE.equals(sample.getMetTarget())) {
                met++;
            }
        }
        stats.setValidSampleCount(durations.size());
        stats.setMissingSampleCount(samples.size() - durations.size());
        stats.setAverageDurationSeconds(average(durations));
        stats.setMedianDurationSeconds(median(durations));
        if (targetHours == null) {
            stats.setMetTargetCount(null);
            stats.setGoalRatio(null);
            return;
        }
        stats.setMetTargetCount(met);
        stats.setGoalRatio(durations.isEmpty() ? null : Math.round(met * 1000.0 / durations.size()) / 10.0);
    }

    private static RequirementDeliveryVO.Sample toSample(DeliveredRequirement row, Long targetHours) {
        RequirementDeliveryVO.Sample sample = new RequirementDeliveryVO.Sample();
        sample.setWorkitemId(row.getWorkitemId());
        sample.setTitle(row.getTitle());
        Instant delivered = row.getDeliveredAt().toInstant();
        sample.setDeliveredAt(delivered.toString());
        Instant started = row.getDeliveryStartedAt() == null ? null : row.getDeliveryStartedAt().toInstant();
        sample.setDeliveryStartedAt(started == null ? null : started.toString());
        if (started == null) {
            sample.setMissingReason("MISSING_DELIVERY_START");
            return sample;
        }
        if (started.isAfter(delivered)) {
            sample.setMissingReason("INVALID_TIMELINE");
            return sample;
        }
        long durationSeconds = Duration.between(started, delivered).getSeconds();
        sample.setDurationSeconds(durationSeconds);
        sample.setMetTarget(targetHours == null ? null : durationSeconds <= targetHours * 3600L);
        return sample;
    }

    /** 上期为 0 时没有可比基数，返回 null 由界面表达「暂无可比基数」，不伪造 100%。 */
    private static Double changePercent(long currentCount, long previousCount) {
        if (previousCount <= 0) {
            return null;
        }
        return Math.round((currentCount - previousCount) * 1000.0 / previousCount) / 10.0;
    }

    private static Long average(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        long sum = 0;
        for (long value : values) {
            sum += value;
        }
        return Math.round((double) sum / values.size());
    }

    private static Long median(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return Math.round((sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0);
    }

    /** 目标只接受 1..MAX 的整数小时；未配置或存量非法值一律按「未设置」处理，不预填虚构目标。 */
    private Long readTargetHours(Long userId) {
        if (userId == null) {
            return null;
        }
        UserSettingVO setting = userSettingService.get(userId, TARGET_SETTING_KEY);
        if (setting == null || setting.getValueJson() == null) {
            return null;
        }
        try {
            Object parsed = JSON.parse(setting.getValueJson());
            if (parsed instanceof Number number) {
                long hours = new BigDecimal(number.toString()).longValueExact();
                if (hours > 0 && hours <= MAX_TARGET_HOURS) {
                    return hours;
                }
            }
        } catch (RuntimeException malformedStoredValue) {
            // 存量非法 JSON 按未设置处理，不阻断整个指标查询。
        }
        return null;
    }

    private static Date atStart(LocalDate date) {
        return Date.from(date.atStartOfDay(ZONE).toInstant());
    }
}
