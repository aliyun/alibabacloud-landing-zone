package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class SdlcDriverTest {

    private WorkitemDao workitemDao;
    private SdlcStepDao stepDao;
    private SdlcDriver driver;

    private static final long TENANT = 100L;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        stepDao = mock(SdlcStepDao.class);
        driver = new SdlcDriver(workitemDao, mock(WorkitemService.class), stepDao,
                mock(StatusNodeDao.class), mock(AgentRoleResolver.class));
    }

    @Test
    void successStopsWithoutReadingDeprecatedRoutingFields() {
        WorkitemDO w = workitem();
        SdlcStepDO step = step();
        step.setOnSuccess("{\"action\":\"GOTO_STEP\",\"targetStepId\":301}");
        step.setHandlerType("AGENT");
        step.setHandlerRoleRef("QA");
        when(workitemDao.findById(200L)).thenReturn(w);
        when(stepDao.findById(300L)).thenReturn(step);

        DriveResult r = driver.onSuccess(TENANT, 200L, 300L);

        assertEquals(DriveResult.Kind.STOP, r.getKind());
        verify(workitemDao, never()).updateCurrentStep(anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
        verify(workitemDao, never()).updateAssignee(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong());
    }

    @Test
    void failStopsWithoutRetryOrHumanHandoffFromDeprecatedFields() {
        WorkitemDO w = workitem();
        SdlcStepDO step = step();
        step.setOnFail("{\"action\":\"RETRY\",\"maxAttempts\":3}");
        when(workitemDao.findById(200L)).thenReturn(w);
        when(stepDao.findById(300L)).thenReturn(step);

        DriveResult r = driver.onFail(TENANT, 200L, 300L);

        assertEquals(DriveResult.Kind.STOP, r.getKind());
        verify(workitemDao, never()).updateCurrentStep(anyLong(), anyLong(), anyLong(), anyInt(), anyLong());
        verify(workitemDao, never()).updateAssignee(anyLong(), anyLong(), anyString(), any(), anyInt(), anyLong());
    }

    @Test
    void tenantMismatchStops() {
        WorkitemDO w = workitem();
        w.setTenantId(999L);
        when(workitemDao.findById(200L)).thenReturn(w);
        when(stepDao.findById(300L)).thenReturn(step());

        DriveResult r = driver.onSuccess(TENANT, 200L, 300L);

        assertEquals(DriveResult.Kind.STOP, r.getKind());
    }

    private WorkitemDO workitem() {
        WorkitemDO w = new WorkitemDO();
        w.setId(200L);
        w.setTenantId(TENANT);
        w.setCurrentStepId(300L);
        w.setVersion(0);
        return w;
    }

    private SdlcStepDO step() {
        SdlcStepDO s = new SdlcStepDO();
        s.setId(300L);
        s.setTenantId(TENANT);
        s.setSdlcId(9L);
        s.setStepOrder(1);
        return s;
    }
}
