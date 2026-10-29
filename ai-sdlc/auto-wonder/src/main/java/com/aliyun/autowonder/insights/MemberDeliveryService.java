package com.aliyun.autowonder.insights;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class MemberDeliveryService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> WORK_TYPES = Set.of("REQ", "BUG", "TASK");
    private final MemberDeliveryDao dao;
    public MemberDeliveryService(MemberDeliveryDao dao) { this.dao = dao; }
    @Getter @Setter public static class Counts {
        private Long memberId;
        private long created, total, completed, inProgress, notStarted, pendingDecision, requirements;
        void add(Counts c) {
            created += c.created; total += c.total; completed += c.completed; inProgress += c.inProgress;
            notStarted += c.notStarted; pendingDecision += c.pendingDecision; requirements += c.requirements;
        }
    }
    /** 周期计数按 (memberId, workType) 分组返回：同一次查询既满足 summary 的类型过滤，也保留 members 的全类型明细。 */
    @Getter @Setter public static class TypeCounts extends Counts {
        private String workType;
    }
    @Getter @Setter public static class Member extends Counts {
        private String memberName;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String memberNameKind;
    }
    public record Report(String startDate, String endDate, String timezone, Counts summary, List<Member> members) {}
    public Report get(long tenantId, String from, String to) {
        return get(tenantId, from, to, null);
    }
    public Report get(long tenantId, String from, String to, String workType) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate start, end;
        try {
            start = from == null ? today.minusDays(29) : LocalDate.parse(from);
            end = to == null ? today : LocalDate.parse(to);
        } catch (RuntimeException e) { throw new BizException(ErrorCode.PARAM_INVALID); }
        if (start.isAfter(end) || end.isAfter(today) || start.isBefore(today.minusDays(180)))
            throw new BizException(ErrorCode.PARAM_INVALID);
        String type = normalizeWorkType(workType);
        Map<Long, Member> members = new LinkedHashMap<>();
        for (Member member : dao.members(tenantId)) members.put(member.getMemberId(), member);
        Counts summary = new Counts();
        for (TypeCounts counts : query(tenantId, start, end)) {
            Member member = members.computeIfAbsent(counts.getMemberId(), id -> {
                Member m = new Member(); m.setMemberId(id);
                m.setMemberName(id == null ? "未归属成员" : "历史成员 #" + id);
                if (id != null) m.setMemberNameKind("HISTORICAL_MEMBER_FALLBACK");
                return m;
            });
            // members 明细始终累加全部类型，工单类型只收敛 summary，接口层锁死表格作用域。
            member.add(counts);
            if (type == null || type.equals(counts.getWorkType())) summary.add(counts);
        }
        return new Report(start.toString(), end.toString(), ZONE.getId(), summary, new ArrayList<>(members.values()));
    }
    private static String normalizeWorkType(String workType) {
        if (workType == null) return null;
        String type = workType.trim();
        if (type.isEmpty() || "ALL".equals(type)) return null;
        if (!WORK_TYPES.contains(type)) throw new BizException(ErrorCode.PARAM_INVALID);
        return type;
    }
    private List<TypeCounts> query(long tenantId, LocalDate start, LocalDate end) {
        return dao.counts(tenantId, Date.from(start.atStartOfDay(ZONE).toInstant()),
                Date.from(end.plusDays(1).atStartOfDay(ZONE).toInstant()));
    }
}
