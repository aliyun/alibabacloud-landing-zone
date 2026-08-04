package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.integration.dto.AoneSyncResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalWorkitemSyncServiceTest {

    @Test
    void syncsLocalWorkitemThroughItsExternalLink() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        ExternalWorkitemSyncService service = new ExternalWorkitemSyncService(linkDao, bindingDao, inboundSyncService);
        ExternalWorkitemLinkDO link = link();
        ExternalProjectBindingDO binding = binding();
        AoneSyncResult expected = new AoneSyncResult();
        expected.setUpdated(1);

        when(linkDao.findByWorkitem(100L, "AONE", 500L)).thenReturn(link);
        when(bindingDao.findById(1L)).thenReturn(binding);
        when(inboundSyncService.refreshIssueIds(binding, List.of("84189105"), 9L)).thenReturn(expected);

        AoneSyncResult result = service.syncLocalWorkitem(500L, 100L, 9L);

        assertEquals(1, result.getUpdated());
        verify(inboundSyncService).refreshIssueIds(binding, List.of("84189105"), 9L);
    }

    @Test
    void rejectsWorkitemWithoutAoneLink() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        ExternalWorkitemSyncService service = new ExternalWorkitemSyncService(linkDao, bindingDao, inboundSyncService);

        when(linkDao.findByWorkitem(100L, "AONE", 500L)).thenReturn(null);

        BizException error = assertThrows(BizException.class, () -> service.syncLocalWorkitem(500L, 100L, 9L));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), error.getCode());
    }

    private ExternalWorkitemLinkDO link() {
        ExternalWorkitemLinkDO link = new ExternalWorkitemLinkDO();
        link.setId(10L);
        link.setTenantId(100L);
        link.setProvider("AONE");
        link.setBindingId(1L);
        link.setWorkitemId(500L);
        link.setExternalWorkitemId("84189105");
        return link;
    }

    private ExternalProjectBindingDO binding() {
        ExternalProjectBindingDO binding = new ExternalProjectBindingDO();
        binding.setId(1L);
        binding.setTenantId(100L);
        binding.setProvider("AONE");
        binding.setExternalProjectId("2161074");
        return binding;
    }
}
