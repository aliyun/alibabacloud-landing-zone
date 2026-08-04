package com.aliyun.autowonder.statemachine;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatusTemplateSeederTest {

    private StatusTemplateDao templateDao;
    private StatusNodeDao nodeDao;
    private StatusTransitionDao transitionDao;
    private StatusTemplateSeeder seeder;

    @BeforeEach
    void setUp() {
        templateDao = mock(StatusTemplateDao.class);
        nodeDao = mock(StatusNodeDao.class);
        transitionDao = mock(StatusTransitionDao.class);
        AtomicLong tplSeq = new AtomicLong(1);
        doAnswer(inv -> { ((StatusTemplateDO) inv.getArgument(0)).setId(tplSeq.getAndIncrement()); return null; })
                .when(templateDao).insert(any());
        AtomicLong nodeSeq = new AtomicLong(100);
        doAnswer(inv -> { ((StatusNodeDO) inv.getArgument(0)).setId(nodeSeq.getAndIncrement()); return null; })
                .when(nodeDao).insert(any());
        seeder = new StatusTemplateSeeder(templateDao, nodeDao, transitionDao);
    }

    @Test
    void seeds_three_default_templates() {
        seeder.seed(100L, 7L);
        ArgumentCaptor<StatusTemplateDO> cap = ArgumentCaptor.forClass(StatusTemplateDO.class);
        verify(templateDao, times(3)).insert(cap.capture());
        assertTrue(cap.getAllValues().stream().allMatch(t -> t.getIsDefault() == 1));
        assertTrue(cap.getAllValues().stream().allMatch(t -> t.getTenantId() == 100L));
        assertTrue(cap.getAllValues().stream().anyMatch(t -> "REQ".equals(t.getWorkType())));
        assertTrue(cap.getAllValues().stream().anyMatch(t -> "TASK".equals(t.getWorkType())));
        assertTrue(cap.getAllValues().stream().anyMatch(t -> "BUG".equals(t.getWorkType())));
    }

    @Test
    void seeds_nodes_and_transitions() {
        seeder.seed(100L, 7L);
        // REQ 5 + TASK 3 + BUG 4 = 12 个节点
        verify(nodeDao, times(12)).insert(any(StatusNodeDO.class));
        // REQ 6(线性3+取消3) + TASK 2 + BUG 3 = 11 条流转
        verify(transitionDao, times(11)).insert(any(StatusTransitionDO.class));
    }

    @Test
    void each_template_has_one_init_node() {
        seeder.seed(100L, 7L);
        ArgumentCaptor<StatusNodeDO> cap = ArgumentCaptor.forClass(StatusNodeDO.class);
        verify(nodeDao, times(12)).insert(cap.capture());
        long initCount = cap.getAllValues().stream()
                .filter(n -> "INIT".equals(n.getCategory())).count();
        assertEquals(3, initCount);
    }
}
