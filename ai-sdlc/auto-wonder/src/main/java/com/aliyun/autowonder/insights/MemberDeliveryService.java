package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Service
public class MemberDeliveryService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final MemberDeliveryDao dao;
    public MemberDeliveryService(MemberDeliveryDao dao) { this.dao = dao; }
    @Getter @Setter public static class Counts {
        private Long memberId;
        private long total, completed, inProgress, requirements;
        void add(Counts c) { total += c.total; completed += c.completed; inProgress += c.inProgress; requirements += c.requirements; }
    }
    @Getter @Setter public static class Member extends Counts { private String memberName; }
    public record Report(String startDate, String endDate, String timezone, Counts summary,
                         long weekRequirements, List<Member> members) {}
    public Report get(long tenantId, String from, String to) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate start, end;
        try {
            start = from == null ? monday : LocalDate.parse(from);
            end = to == null ? today : LocalDate.parse(to);
        } catch (RuntimeException e) { throw new BizException(ErrorCode.PARAM_INVALID); }
        if (start.isAfter(end) || end.isAfter(today) || start.plusDays(365).isBefore(end))
            throw new BizException(ErrorCode.PARAM_INVALID);
        Map<Long, Member> members = new LinkedHashMap<>();
        for (Member member : dao.members(tenantId)) members.put(member.getMemberId(), member);
        Counts summary = new Counts();
        for (Counts counts : query(tenantId, start, end)) {
            summary.add(counts);
            Member member = members.computeIfAbsent(counts.getMemberId(), id -> {
                Member m = new Member(); m.setMemberId(id);
                m.setMemberName(id == null ? "未归属成员" : "历史成员 #" + id); return m;
            });
            member.add(counts);
        }
        long week = start.equals(monday) && end.equals(today) ? summary.getRequirements()
                : query(tenantId, monday, today).stream().mapToLong(Counts::getRequirements).sum();
        return new Report(start.toString(), end.toString(), ZONE.getId(), summary, week, new ArrayList<>(members.values()));
    }
    private List<Counts> query(long tenantId, LocalDate start, LocalDate end) {
        return dao.counts(tenantId, Date.from(start.atStartOfDay(ZONE).toInstant()),
                Date.from(end.plusDays(1).atStartOfDay(ZONE).toInstant()));
    }
}
