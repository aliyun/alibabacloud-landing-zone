package com.aliyun.autowonder.dashboard;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dashboard.dto.CompletedWorkitemVO;
import com.aliyun.autowonder.dashboard.dto.HealthVO;
import com.aliyun.autowonder.dashboard.dto.InventoryVO;
import com.aliyun.autowonder.dashboard.dto.KpiVO;
import com.aliyun.autowonder.dashboard.dto.RealtimeDashboardVO;
import com.aliyun.autowonder.dashboard.dto.RunningTaskVO;
import com.aliyun.autowonder.dashboard.dto.SquadLineVO;
import com.aliyun.autowonder.dashboard.dto.WorkstationVO;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private static final int FEED_LIMIT = 10;

    private final DashboardDao dao;

    public DashboardService(DashboardDao dao) {
        this.dao = dao;
    }

    public RealtimeDashboardVO getRealtime(long tenantId) {
        RealtimeDashboardVO vo = new RealtimeDashboardVO();
        vo.setKpi(buildKpi(tenantId));
        vo.setInventory(buildInventory(tenantId));
        vo.setSquads(buildSquads(tenantId));
        vo.setWorkstations(buildWorkstations(tenantId));
        vo.setHealth(buildHealth(tenantId));
        vo.setRunningFeed(dao.listRunningFeed(tenantId, FEED_LIMIT));
        vo.setRecentFeed(dao.listRecentFeed(tenantId, FEED_LIMIT));
        vo.setGeneratedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        return vo;
    }

    public List<RunningTaskVO> getAgentRunning(long tenantId, long agentId) {
        if (dao.agentExists(tenantId, agentId) == 0) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        return dao.listAgentRunning(tenantId, agentId);
    }

    public List<CompletedWorkitemVO> getTodayCompleted(long tenantId) {
        return dao.listTodayCompletedWorkitems(tenantId);
    }

    public List<CompletedWorkitemVO> getWeekCompleted(long tenantId) {
        return dao.listWeekCompletedWorkitems(tenantId);
    }

    public List<RunningTaskVO> getRunningWorkitems(long tenantId) {
        return dao.listRunningWorkitems(tenantId);
    }

    private KpiVO buildKpi(long tenantId) {
        KpiVO kpi = new KpiVO();
        int running = dao.countRunningDispatches(tenantId);
        int online = dao.countOnlineAgents(tenantId);
        Integer avgTaskDuration = dao.avgTodayCompletedTaskDurationMinutes(tenantId);
        kpi.setRunningDispatches(running);
        kpi.setTodayCompletedTasks(dao.countTodayCompletedTasks(tenantId));
        kpi.setWeekCompletedTasks(dao.countWeekCompletedTasks(tenantId));
        kpi.setAvgTaskDurationMinutes(avgTaskDuration != null ? avgTaskDuration : 0);
        kpi.setInProgressWorkitems(dao.countInProgressWorkitems(tenantId));
        kpi.setQueuedDispatches(dao.countQueuedDispatches(tenantId));
        kpi.setActiveSquads(dao.countActiveSquads(tenantId));
        kpi.setOnlineAgents(online);
        kpi.setAvgLoad(online > 0 ? round2((double) running / online) : 0.0);
        return kpi;
    }

    private InventoryVO buildInventory(long tenantId) {
        InventoryVO inv = new InventoryVO();
        for (Map<String, Object> row : dao.countWorkitemsByLifecycle(tenantId)) {
            String category = str(row.get("category"));
            int cnt = intVal(row.get("cnt"));
            if ("INIT".equals(category)) {
                inv.getByLifecycle().setInit(cnt);
            } else if ("IN_PROGRESS".equals(category)) {
                inv.getByLifecycle().setInProgress(cnt);
            } else if ("DONE".equals(category)) {
                inv.getByLifecycle().setDone(cnt);
            } else if ("CANCELED".equals(category)) {
                inv.getByLifecycle().setCanceled(cnt);
            }
        }
        for (Map<String, Object> row : dao.countWorkitemsByType(tenantId)) {
            String type = str(row.get("workType"));
            int cnt = intVal(row.get("cnt"));
            if ("REQ".equals(type)) {
                inv.getByType().setReq(cnt);
            } else if ("TASK".equals(type)) {
                inv.getByType().setTask(cnt);
            } else if ("BUG".equals(type)) {
                inv.getByType().setBug(cnt);
            }
        }
        return inv;
    }

    private List<SquadLineVO> buildSquads(long tenantId) {
        Map<Long, Integer> inProgressBySquad = new HashMap<>();
        for (Map<String, Object> row : dao.squadInProgressWorkitems(tenantId)) {
            inProgressBySquad.put(longVal(row.get("squadId")), intVal(row.get("cnt")));
        }
        List<SquadLineVO> result = new ArrayList<>();
        for (Map<String, Object> row : dao.squadLineAggregates(tenantId)) {
            SquadLineVO vo = new SquadLineVO();
            Long squadId = longVal(row.get("squadId"));
            int members = intVal(row.get("members"));
            int runningTasks = intVal(row.get("runningTasks"));
            vo.setSquadId(squadId);
            vo.setName(str(row.get("name")));
            vo.setMembers(members);
            vo.setOnline(intVal(row.get("online")));
            vo.setBusy(intVal(row.get("busy")));
            vo.setRunningTasks(runningTasks);
            vo.setInProgressWorkitems(inProgressBySquad.getOrDefault(squadId, 0));
            vo.setLoad(members > 0 ? round2((double) runningTasks / members) : 0.0);
            result.add(vo);
        }
        return result;
    }

    private List<WorkstationVO> buildWorkstations(long tenantId) {
        List<WorkstationVO> result = new ArrayList<>();
        for (Map<String, Object> row : dao.onlineWorkstations(tenantId)) {
            WorkstationVO vo = new WorkstationVO();
            int runningTasks = intVal(row.get("runningTasks"));
            vo.setAgentId(longVal(row.get("agentId")));
            vo.setName(str(row.get("name")));
            vo.setAvatarUrl(str(row.get("avatarUrl")));
            vo.setRunningTasks(runningTasks);
            vo.setBusy(runningTasks > 0);
            result.add(vo);
        }
        return result;
    }

    private HealthVO buildHealth(long tenantId) {
        HealthVO health = new HealthVO();
        int succeeded = dao.countTodaySucceeded(tenantId);
        int failedOrTimeout = dao.countTodayFailedOrTimeout(tenantId);
        int terminal = succeeded + failedOrTimeout;
        health.setSuccessRate(terminal > 0 ? round1((double) succeeded / terminal * 100.0) : 0.0);
        health.setFailedOrTimeout(failedOrTimeout);
        health.setRetries(dao.countTodayRetries(tenantId));
        Integer avg = dao.avgTodaySuccessDurationMinutes(tenantId);
        health.setAvgDurationMinutes(avg != null ? avg : 0);
        return health;
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static int intVal(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static Long longVal(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }
}
