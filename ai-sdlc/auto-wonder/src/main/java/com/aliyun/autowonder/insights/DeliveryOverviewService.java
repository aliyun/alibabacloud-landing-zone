package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.common.error.KeyedIllegalArgumentException;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.insights.dto.CreatedWorkitemVO;
import com.aliyun.autowonder.insights.dto.DeliveryOverviewVO;
import com.aliyun.autowonder.insights.dto.PendingWorkitemVO;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 交付总览：区间内新增工单（含按日趋势与分页明细）与交付吞吐（含环比），以及当前登录人的待处理工单。
 * <p>
 * 新增口径与趋势、明细列表共用同一 SQL 片段：区间 [start, end) 内创建的工单，含取消工单的新建，
 * 故明细列表条数恒等于「新增工单」统计卡数值。
 * <p>
 * 完成口径与 {@code RequirementDeliveryService} 一致：当前处于 DONE 类状态节点的工单，
 * 完成时间 = 最近一次进入 DONE 类节点的状态事件时间（重开后再完成取最近一次，按 workitem 去重），
 * 不从当前状态反推历史完成时间，取消不计。上一周期为紧邻的等长区间，上期为 0 时无可比基数。
 * 按日分桶与日期边界均在 Asia/Shanghai 自然日下计算。
 */
@Service
public class DeliveryOverviewService {

    /** 待办原因：待决策（工单处于待决策分类）。 */
    public static final String REASON_AWAITING_DECISION = "AWAITING_DECISION";
    /** 待办原因：待处理（尚未开始处理）。 */
    public static final String REASON_AWAITING_HANDLING = "AWAITING_HANDLING";
    /** 待办原因：处理中。 */
    public static final String REASON_IN_PROGRESS = "IN_PROGRESS";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_RANGE_DAYS = 366;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final DeliveryOverviewDao dao;

    public DeliveryOverviewService(DeliveryOverviewDao dao) {
        this.dao = dao;
    }

    /** 区间内创建的工单取数行；名称与 mapping 的 resultType 对齐。 */
    @Getter
    @Setter
    public static class CreatedWorkitem {
        private long id;
        private Date createdAt;
    }

    /** 区间内新建工单明细取数行；名称与 mapping 的 resultType 对齐。 */
    @Getter
    @Setter
    public static class CreatedWorkitemRow {
        private long id;
        private String title;
        private String statusName;
        private String statusCategory;
        private int priority;
        private Date createdAt;
    }

    /** 当前用户待处理工单取数行；名称与 mapping 的 resultType 对齐。 */
    @Getter
    @Setter
    public static class PendingWorkitemRow {
        private long id;
        private String title;
        private String statusName;
        private String statusCategory;
        private int priority;
        private Date gmtModified;
    }

    public DeliveryOverviewVO overview(long tenantId, LocalDate start, LocalDate end) {
        LocalDate today = LocalDate.now(ZONE);
        int daysInRange = validateRange(start, end, today);
        long spanDays = daysInRange - 1L;
        LocalDate previousEnd = start.minusDays(1);
        LocalDate previousStart = previousEnd.minusDays(spanDays);

        List<CreatedWorkitem> createdRows = dao.listCreatedWorkitems(
                tenantId, atStart(start), atStart(end.plusDays(1)));
        long deliveredCount = dao.countDeliveredWorkitems(
                tenantId, atStart(start), atStart(end.plusDays(1)));
        long previousCount = dao.countDeliveredWorkitems(
                tenantId, atStart(previousStart), atStart(start));

        DeliveryOverviewVO vo = new DeliveryOverviewVO();
        vo.setStartDate(start.toString());
        vo.setEndDate(end.toString());
        vo.setTimezone(ZONE.getId());
        vo.setDataThrough(today.toString());
        vo.setDaysInRange(daysInRange);
        vo.setCreated(buildCreated(start, daysInRange, createdRows));
        vo.setDelivered(buildDelivered(deliveredCount, daysInRange, previousStart, previousEnd, previousCount));
        return vo;
    }

    /** 区间内新建工单分页明细；与新增趋势同区间口径（含区间校验），创建时间降序。 */
    public PageResult<CreatedWorkitemVO> created(long tenantId, LocalDate start, LocalDate end,
                                                 Integer page, Integer size) {
        validateRange(start, end, LocalDate.now(ZONE));
        int pageNum = normalizePageNum(page);
        int pageSize = normalizePageSize(size);
        Date from = atStart(start);
        Date to = atStart(end.plusDays(1));
        long total = dao.countCreatedWorkitems(tenantId, from, to);
        List<CreatedWorkitemRow> rows = dao.listCreatedWorkitemsPage(
                tenantId, from, to, pageOffset(pageNum, pageSize), pageSize);
        List<CreatedWorkitemVO> items = new ArrayList<>(rows.size());
        for (CreatedWorkitemRow row : rows) {
            items.add(toCreatedWorkitem(row));
        }
        return new PageResult<>(items, total, pageNum, pageSize);
    }

    public PageResult<PendingWorkitemVO> pending(long tenantId, long userId, Integer page, Integer size) {
        int pageNum = normalizePageNum(page);
        int pageSize = normalizePageSize(size);
        long total = dao.countPendingWorkitems(tenantId, userId);
        List<PendingWorkitemRow> rows = dao.listPendingWorkitems(
                tenantId, userId, pageOffset(pageNum, pageSize), pageSize);
        List<PendingWorkitemVO> items = new ArrayList<>(rows.size());
        for (PendingWorkitemRow row : rows) {
            items.add(toPendingWorkitem(row));
        }
        return new PageResult<>(items, total, pageNum, pageSize);
    }

    /** 区间校验：null、逆序、晚于今天、跨度超上限均拒绝；返回含首尾的区间天数。 */
    private static int validateRange(LocalDate start, LocalDate end, LocalDate today) {
        if (start == null || end == null || start.isAfter(end) || end.isAfter(today)
                || ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw new KeyedIllegalArgumentException("insights.invalid_date_range", null,
                    "Invalid delivery overview range: " + start + ".." + end + " (today=" + today + ")");
        }
        return (int) ChronoUnit.DAYS.between(start, end) + 1;
    }

    private static int normalizePageNum(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private static int normalizePageSize(Integer size) {
        return size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    }

    /** 超大 page 下 offset 超出 int 范围，必须以 long 计算，避免溢出为负导致 SQL 报错。 */
    private static long pageOffset(int pageNum, int pageSize) {
        return (long) (pageNum - 1) * pageSize;
    }

    /** 区间内逐日骨架 + 零填充，趋势口径固定为 Asia/Shanghai 自然日。 */
    private static DeliveryOverviewVO.Created buildCreated(LocalDate start, int daysInRange,
                                                           List<CreatedWorkitem> rows) {
        Map<LocalDate, Long> counts = new HashMap<>();
        for (CreatedWorkitem row : rows) {
            LocalDate date = row.getCreatedAt().toInstant().atZone(ZONE).toLocalDate();
            counts.merge(date, 1L, Long::sum);
        }
        List<DeliveryOverviewVO.DailyCount> daily = new ArrayList<>(daysInRange);
        for (int i = 0; i < daysInRange; i++) {
            LocalDate date = start.plusDays(i);
            DeliveryOverviewVO.DailyCount bucket = new DeliveryOverviewVO.DailyCount();
            bucket.setDate(date.toString());
            bucket.setCount(counts.getOrDefault(date, 0L));
            daily.add(bucket);
        }
        DeliveryOverviewVO.Created created = new DeliveryOverviewVO.Created();
        created.setCount(rows.size());
        created.setDaily(daily);
        return created;
    }

    private static DeliveryOverviewVO.Delivered buildDelivered(long count, int daysInRange,
                                                               LocalDate previousStart, LocalDate previousEnd,
                                                               long previousCount) {
        DeliveryOverviewVO.Delivered delivered = new DeliveryOverviewVO.Delivered();
        delivered.setCount(count);
        delivered.setDailyAverage(Math.round(count * 10.0 / daysInRange) / 10.0);
        DeliveryOverviewVO.Period period = new DeliveryOverviewVO.Period();
        period.setStartDate(previousStart.toString());
        period.setEndDate(previousEnd.toString());
        period.setCount(previousCount);
        delivered.setPrevious(period);
        delivered.setChangePercent(changePercent(count, previousCount));
        return delivered;
    }

    private static CreatedWorkitemVO toCreatedWorkitem(CreatedWorkitemRow row) {
        CreatedWorkitemVO vo = new CreatedWorkitemVO();
        vo.setId(row.getId());
        vo.setTitle(row.getTitle());
        vo.setStatusName(row.getStatusName());
        vo.setStatusCategory(row.getStatusCategory());
        vo.setPriority(row.getPriority());
        vo.setCreatedAt(row.getCreatedAt().toInstant().toString());
        return vo;
    }

    private static PendingWorkitemVO toPendingWorkitem(PendingWorkitemRow row) {
        PendingWorkitemVO vo = new PendingWorkitemVO();
        vo.setId(row.getId());
        vo.setTitle(row.getTitle());
        vo.setStatusName(row.getStatusName());
        vo.setStatusCategory(row.getStatusCategory());
        vo.setPriority(row.getPriority());
        vo.setReason(reason(row.getStatusCategory()));
        vo.setGmtModified(row.getGmtModified().toInstant().toString());
        return vo;
    }

    /** 需处理原因由看板统一分类派生，与待办筛选保持同一口径。 */
    private static String reason(String statusCategory) {
        if ("PENDING_DECISION".equals(statusCategory)) {
            return REASON_AWAITING_DECISION;
        }
        if ("NEW".equals(statusCategory)) {
            return REASON_AWAITING_HANDLING;
        }
        return REASON_IN_PROGRESS;
    }

    /** 上期为 0 时没有可比基数，返回 null 由界面表达「暂无可比基数」，不伪造增长率。 */
    private static Double changePercent(long currentCount, long previousCount) {
        if (previousCount <= 0) {
            return null;
        }
        return Math.round((currentCount - previousCount) * 1000.0 / previousCount) / 10.0;
    }

    private static Date atStart(LocalDate date) {
        return Date.from(date.atStartOfDay(ZONE).toInstant());
    }
}
