package com.aliyun.autowonder.debuglog;

import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDO;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * debug 日志对象命名：run_no 计算、canonical objectKey 生成与 roleCode 归一化（协议契约「objectKey 规范」）。
 *
 * <p>从 {@link DebugLogService} 抽出的内聚 naming/key 集群（S10 质量审查决策）：签发、TASK_RESULT 补插、
 * 中转重排三条链路共享同一套命名规则，故独立成组件；语义与抽出前逐字一致。
 *
 * <p>不变量沿用 {@link DebugLogService}：命名是纯辅助能力，任何回退都只降级 key 的可读性/聚合能力
 * （并留 warn 痕迹），不得抛给调用方影响派发或结果链路。
 *
 * <p>跨仓库对照实现是 runtime 的 {@code runtime/roundlog/naming.go}（见 {@link #sanitizeRoleCode}
 * javadoc 中记录的三处已知分歧）；中转上传的存储 key 由服务端按 dispatch 权威重排，故 runtime 侧
 * 本地命名漂移不会进入最终 objectKey。
 */
@Component
public class DebugLogObjectNamer {

    private static final Logger log = LoggerFactory.getLogger(DebugLogObjectNamer.class);

    /** debug 日志对象前缀，bucket 生命周期 60 天规则按此前缀配置（docs/autowonder-oss-buckets.md）。 */
    static final String OBJECT_PREFIX = "debug/";

    /** roleCode 段上限，与 runtime/roundlog/naming.go 的 maxSegmentRunes 对齐（按 code point 计）。 */
    static final int MAX_ROLE_CODE_RUNES = 64;

    private final DispatchDao dispatchDao;
    private final AgentVersionDao agentVersionDao;
    private final ScheduledTaskRunDao scheduledTaskRunDao;

    public DebugLogObjectNamer(DispatchDao dispatchDao, AgentVersionDao agentVersionDao,
            ScheduledTaskRunDao scheduledTaskRunDao) {
        this.dispatchDao = dispatchDao;
        this.agentVersionDao = agentVersionDao;
        this.scheduledTaskRunDao = scheduledTaskRunDao;
    }

    /**
     * run_no =（source_type, source_id, agent_id）分组按创建序的 dispatch 序号（协议契约）。
     * 排序键 (gmt_create, id)：同毫秒创建（交接链）也确定；返工/恢复产生的新 dispatch 行
     * 自然得到新的 run_no（设计文档 §6「返工算下一轮」）。
     */
    int computeRunNo(DispatchDO dispatch) {
        List<DispatchDO> siblings = dispatchDao.listBySource(dispatch.getTenantId(),
                dispatch.executionSourceType().name(), dispatch.getWorkitemId());
        List<DispatchDO> sameAgent = new ArrayList<>();
        for (DispatchDO row : siblings) {
            if (row != null && Objects.equals(row.getAgentId(), dispatch.getAgentId())) {
                sameAgent.add(row);
            }
        }
        sameAgent.sort(Comparator
                .comparing(DispatchDO::getGmtCreate, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(DispatchDO::getId));
        for (int i = 0; i < sameAgent.size(); i++) {
            if (Objects.equals(sameAgent.get(i).getId(), dispatch.getId())) {
                return i + 1;
            }
        }
        // 自身不在兄弟列表里（listBySource 读到的快照滞后或分组键不一致）：退化为「已有轮数 + 1」。
        int fallbackRunNo = sameAgent.size() + 1;
        log.warn("debug log run_no fallback dispatchId={} agentId={} runNo={} siblings={} "
                        + "reason=RUN_NO_SELF_NOT_IN_SIBLINGS",
                dispatch.getId(), dispatch.getAgentId(), fallbackRunNo, sameAgent.size());
        return fallbackRunNo;
    }

    /** objectKey 规范见协议契约：key 中不含任何自由文本，全部整数 + 归一化 roleCode。 */
    String canonicalObjectKey(DispatchDO dispatch, int runNo) {
        String role = sanitizeRoleCode(resolveRoleCode(dispatch), dispatch.getAgentId());
        String fileName = role + "-run-" + runNo + ".log.gz";
        if (dispatch.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
            ScheduledTaskRunDO run = scheduledTaskRunDao.findById(dispatch.getTenantId(),
                    dispatch.getWorkitemId());
            Long taskId = run == null ? null : run.getScheduledTaskId();
            if (taskId == null) {
                // key 退化为 scheduled-0-run-{runId}：仍然唯一可寻址，但丢了按任务聚合的能力。
                log.warn("debug log object key degraded dispatchId={} runId={} "
                                + "reason=SCHEDULED_TASK_ID_MISSING",
                        dispatch.getId(), dispatch.getWorkitemId());
                taskId = 0L;
            }
            return OBJECT_PREFIX + "scheduled-" + taskId + "-run-" + dispatch.getWorkitemId()
                    + "/" + fileName;
        }
        return OBJECT_PREFIX + dispatch.getWorkitemId() + "/" + fileName;
    }

    /**
     * 解析 roleCode；任何回退都返回 null，由 {@link #sanitizeRoleCode} 落到 {@code agent-{agentId}}。
     * 每条回退分支各自告警——静默回退会让「key 里为什么没有角色名」变成不可诊断问题。
     */
    private String resolveRoleCode(DispatchDO dispatch) {
        Long agentVersionId = dispatch.getAgentVersionId();
        if (agentVersionId == null) {
            log.warn("debug log role code fallback dispatchId={} agentId={} "
                            + "reason=AGENT_VERSION_ID_MISSING",
                    dispatch.getId(), dispatch.getAgentId());
            return null;
        }
        AgentVersionDO version = agentVersionDao.findById(agentVersionId);
        if (version == null) {
            log.warn("debug log role code fallback dispatchId={} agentVersionId={} "
                            + "reason=AGENT_VERSION_NOT_FOUND",
                    dispatch.getId(), agentVersionId);
            return null;
        }
        if (!Objects.equals(version.getTenantId(), dispatch.getTenantId())) {
            log.warn("debug log role code fallback dispatchId={} agentVersionId={} "
                            + "versionTenantId={} reason=ROLE_CODE_TENANT_MISMATCH",
                    dispatch.getId(), agentVersionId, version.getTenantId());
            return null;
        }
        if (version.getRoleCode() == null || version.getRoleCode().isBlank()) {
            log.warn("debug log role code fallback dispatchId={} agentVersionId={} "
                            + "reason=ROLE_CODE_BLANK",
                    dispatch.getId(), agentVersionId);
            return null;
        }
        return version.getRoleCode();
    }

    /**
     * roleCode 归一化为 {@code [A-Za-z0-9_-]}：非法字符替换为 '-'，按 code point 截断到
     * {@value #MAX_ROLE_CODE_RUNES}，空回退 {@code agent-{agentId}}（协议契约）。
     *
     * <p>对照实现是 runtime 的 {@code runtime/roundlog/naming.go}（SanitizeRoleCode）：字符集与
     * 64 rune 上限一致，用于 runtime 本地文件名与中转 key。三处已知分歧（设计文档 §5.3）：
     * <ul>
     *   <li>空回退：服务端 {@code agent-{agentId}}（objectKey 由服务端权威生成且需自解释），
     *       runtime {@code agent}（agentId 由 LogFileName 另起一段，避免 agent-77-77 重复）。</li>
     *   <li>空白判定：Java {@code strip()}/{@code isBlank()} 基于 {@code Character.isWhitespace}，
     *       NBSP(U+00A0) 不算空白故变成 '-'；Go {@code strings.TrimSpace} 基于
     *       {@code unicode.IsSpace}，NBSP 被修剪掉。</li>
     *   <li>astral 粒度：Java 按 UTF-16 单元产出两个 '-'（emoji），Go 按 rune 产出一个。
     *       此处刻意保留 UTF-16 行为——objectKey 只要求字符集归一，双 dash 无害，而改动会让
     *       已登记 key 与历史对象失配。</li>
     * </ul>
     */
    static String sanitizeRoleCode(String roleCode, long agentId) {
        if (roleCode == null || roleCode.isBlank()) {
            return "agent-" + agentId;
        }
        String stripped = roleCode.strip();
        StringBuilder sb = new StringBuilder(Math.min(stripped.length(), MAX_ROLE_CODE_RUNES));
        int kept = 0;
        for (int i = 0; i < stripped.length() && kept < MAX_ROLE_CODE_RUNES; ) {
            int codePoint = stripped.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            i += charCount;
            kept++;
            if (isKeySafe(codePoint)) {
                sb.appendCodePoint(codePoint);
            } else {
                sb.append("-".repeat(charCount));
            }
        }
        return sb.toString();
    }

    private static boolean isKeySafe(int codePoint) {
        return (codePoint >= 'A' && codePoint <= 'Z') || (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= '0' && codePoint <= '9') || codePoint == '_' || codePoint == '-';
    }
}
