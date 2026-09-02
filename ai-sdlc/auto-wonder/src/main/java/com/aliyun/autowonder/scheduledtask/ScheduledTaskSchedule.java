package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class ScheduledTaskSchedule {

    private static final int MAX_PREVIEW_COUNT = 100;

    public Instant next(String expression, String timezone, Instant after) {
        if (after == null) {
            throw cronInvalid(null);
        }
        try {
            CronExpression cron = parse(expression);
            ZoneId zone = parseZone(timezone);
            ZonedDateTime next = cron.next(after.atZone(zone));
            if (next == null) {
                throw cronInvalid(null);
            }
            return next.toInstant();
        } catch (BizException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw cronInvalid(exception);
        }
    }

    public List<Instant> preview(String expression, String timezone, Instant after, int count) {
        if (count <= 0 || count > MAX_PREVIEW_COUNT) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_VALIDATION_FAILED,
                    "预览次数必须在 1 到 " + MAX_PREVIEW_COUNT + " 之间");
        }
        List<Instant> result = new ArrayList<>(count);
        Set<Instant> unique = new HashSet<>(count);
        Instant cursor = after;
        for (int i = 0; i < count; i++) {
            Instant next = next(expression, timezone, cursor);
            if (!unique.add(next) || !next.isAfter(cursor)) {
                throw cronInvalid(null);
            }
            result.add(next);
            cursor = next;
        }
        return result;
    }

    public void validate(String expression, String timezone) {
        try {
            parse(expression);
            parseZone(timezone);
        } catch (BizException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw cronInvalid(exception);
        }
    }

    private CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()
                || expression.trim().split("\\s+").length != 6) {
            throw cronInvalid(null);
        }
        try {
            return CronExpression.parse(expression.trim());
        } catch (IllegalArgumentException exception) {
            throw cronInvalid(exception);
        }
    }

    private ZoneId parseZone(String timezone) {
        if (timezone == null || timezone.isBlank()
                || !ZoneId.getAvailableZoneIds().contains(timezone.trim())) {
            throw cronInvalid(null);
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException exception) {
            throw cronInvalid(exception);
        }
    }

    private BizException cronInvalid(Throwable cause) {
        return cause == null
                ? new BizException(ErrorCode.SCHEDULED_TASK_CRON_INVALID)
                : new BizException(ErrorCode.SCHEDULED_TASK_CRON_INVALID, cause);
    }
}
