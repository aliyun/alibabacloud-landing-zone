package com.aliyun.autowonder.dispatch.subject;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dispatch.PackageContextAssembler;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkitemExecutionSubjectProviderTest {

    @Test
    void loadFailsClosedWhenWorkitemIsMissing() {
        WorkitemDao dao = mock(WorkitemDao.class);
        WorkitemExecutionSubjectProvider provider =
                new WorkitemExecutionSubjectProvider(dao, mock(PackageContextAssembler.class));

        BizException failure = assertThrows(BizException.class, () -> provider.load(100L, 50001L));

        assertEquals("13003", failure.getCode());
    }

    @Test
    void sameNumericIdFromAnotherTenantCannotBecomeExecutionSubject() {
        WorkitemDao dao = mock(WorkitemDao.class);
        WorkitemDO foreign = new WorkitemDO();
        foreign.setId(50001L);
        foreign.setTenantId(200L);
        when(dao.findById(50001L)).thenReturn(foreign);
        WorkitemExecutionSubjectProvider provider =
                new WorkitemExecutionSubjectProvider(dao, mock(PackageContextAssembler.class));

        BizException failure = assertThrows(BizException.class, () -> provider.load(100L, 50001L));

        assertEquals("13003", failure.getCode());
    }

    @Test
    void loadCarriesOnlyTenantValidatedWorkitem() {
        WorkitemDao dao = mock(WorkitemDao.class);
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(50001L);
        workitem.setTenantId(100L);
        when(dao.findById(50001L)).thenReturn(workitem);
        WorkitemExecutionSubjectProvider provider =
                new WorkitemExecutionSubjectProvider(dao, mock(PackageContextAssembler.class));

        ExecutionSubject subject = provider.load(100L, 50001L);

        assertEquals(50001L, subject.ref().id());
    }
}
