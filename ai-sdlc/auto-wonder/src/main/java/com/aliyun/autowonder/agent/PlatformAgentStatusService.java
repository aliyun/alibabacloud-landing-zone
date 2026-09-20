package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.agent.dto.PlatformAgentStatusVO;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.aliyun.autowonder.agent.dto.PlatformAgentStatusVO.STATE_NOT_CONFIGURED;
import static com.aliyun.autowonder.agent.dto.PlatformAgentStatusVO.STATE_OFFLINE;
import static com.aliyun.autowonder.agent.dto.PlatformAgentStatusVO.STATE_OK;

/** 汇总当前工作空间平台数字人 Chief of Staff 的执行器配置与在线状态。 */
@Service
public class PlatformAgentStatusService {

    private final AgentDao agentDao;
    private final ExecutorDao executorDao;
    private final ExecutorRegistry executorRegistry;

    public PlatformAgentStatusService(AgentDao agentDao, ExecutorDao executorDao,
                                      ExecutorRegistry executorRegistry) {
        this.agentDao = agentDao;
        this.executorDao = executorDao;
        this.executorRegistry = executorRegistry;
    }

    public PlatformAgentStatusVO getStatus(long tenantId) {
        AgentDO agent = agentDao.findPlatformAgent(tenantId);
        if (agent == null) {
            return build(STATE_NOT_CONFIGURED, null, 0, 0);
        }
        List<ExecutorDO> executors = executorDao.listByAgent(tenantId, agent.getId());
        int total = executors == null ? 0 : executors.size();
        int online = 0;
        if (executors != null) {
            for (ExecutorDO executor : executors) {
                if (executor.getId() != null && executorRegistry.isOnline(executor.getId())) {
                    online++;
                }
            }
        }
        String state = total == 0 ? STATE_NOT_CONFIGURED : (online == 0 ? STATE_OFFLINE : STATE_OK);
        return build(state, agent.getId(), total, online);
    }

    private PlatformAgentStatusVO build(String state, Long agentId, int executorCount, int onlineExecutorCount) {
        PlatformAgentStatusVO vo = new PlatformAgentStatusVO();
        vo.setState(state);
        vo.setAgentId(agentId);
        vo.setExecutorCount(executorCount);
        vo.setOnlineExecutorCount(onlineExecutorCount);
        return vo;
    }
}
