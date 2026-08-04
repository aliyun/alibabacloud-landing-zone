package com.aliyun.autowonder.statemachine;

import org.springframework.stereotype.Component;

@Component
public class StatusTemplateSeeder {

    private final StatusTemplateDao templateDao;
    private final StatusNodeDao nodeDao;
    private final StatusTransitionDao transitionDao;

    public StatusTemplateSeeder(StatusTemplateDao templateDao, StatusNodeDao nodeDao,
                                StatusTransitionDao transitionDao) {
        this.templateDao = templateDao;
        this.nodeDao = nodeDao;
        this.transitionDao = transitionDao;
    }

    /** 为新组织播种 REQ/TASK/BUG 三套默认状态模版(节点 + 流转边)。 */
    public void seed(long tenantId, long creatorId) {
        seedTemplate(tenantId, creatorId, "REQ", "需求默认流程", new String[][]{
                {"new", "新建", "INIT"},
                {"developing", "开发中", "IN_PROGRESS"},
                {"verifying", "验证中", "IN_PROGRESS"},
                {"released", "已发布", "DONE"},
                {"canceled", "已取消", "CANCELED"}}, true);
        seedTemplate(tenantId, creatorId, "TASK", "任务默认流程", new String[][]{
                {"todo", "待办", "INIT"},
                {"doing", "进行中", "IN_PROGRESS"},
                {"done", "已完成", "DONE"}}, false);
        seedTemplate(tenantId, creatorId, "BUG", "缺陷默认流程", new String[][]{
                {"open", "待修复", "INIT"},
                {"fixing", "修复中", "IN_PROGRESS"},
                {"verifying", "验证中", "IN_PROGRESS"},
                {"closed", "已关闭", "DONE"}}, false);
    }

    private void seedTemplate(long tenantId, long creatorId, String workType, String name,
                              String[][] nodes, boolean withCancel) {
        StatusTemplateDO tpl = new StatusTemplateDO();
        tpl.setTenantId(tenantId);
        tpl.setWorkType(workType);
        tpl.setName(name);
        tpl.setIsDefault(1);
        tpl.setCreatorId(creatorId);
        templateDao.insert(tpl);
        long templateId = tpl.getId();

        long[] nodeIds = new long[nodes.length];
        Long cancelNodeId = null;
        for (int i = 0; i < nodes.length; i++) {
            StatusNodeDO node = new StatusNodeDO();
            node.setTenantId(tenantId);
            node.setTemplateId(templateId);
            node.setCode(nodes[i][0]);
            node.setName(nodes[i][1]);
            node.setCategory(nodes[i][2]);
            node.setSort(i);
            nodeDao.insert(node);
            long nodeId = node.getId();
            nodeIds[i] = nodeId;
            if ("CANCELED".equals(nodes[i][2])) {
                cancelNodeId = nodeId;
            }
        }
        // 线性正向流转:相邻非取消节点
        Integer prevIdx = null;
        for (int i = 0; i < nodes.length; i++) {
            if ("CANCELED".equals(nodes[i][2])) continue;
            if (prevIdx != null) {
                insertTransition(tenantId, templateId, nodeIds[prevIdx], nodeIds[i], "前进");
            }
            prevIdx = i;
        }
        // 任意非终态 → canceled
        if (withCancel && cancelNodeId != null) {
            for (int i = 0; i < nodes.length; i++) {
                String cat = nodes[i][2];
                if ("INIT".equals(cat) || "IN_PROGRESS".equals(cat)) {
                    insertTransition(tenantId, templateId, nodeIds[i], cancelNodeId, "取消");
                }
            }
        }
    }

    private void insertTransition(long tenantId, long templateId, long fromNodeId, long toNodeId, String name) {
        StatusTransitionDO tr = new StatusTransitionDO();
        tr.setTenantId(tenantId);
        tr.setTemplateId(templateId);
        tr.setFromNodeId(fromNodeId);
        tr.setToNodeId(toNodeId);
        tr.setName(name);
        transitionDao.insert(tr);
    }
}
