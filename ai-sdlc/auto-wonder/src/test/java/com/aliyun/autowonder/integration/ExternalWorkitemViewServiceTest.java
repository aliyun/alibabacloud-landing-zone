package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.integration.common.ExternalPrincipalDO;
import com.aliyun.autowonder.integration.common.ExternalPrincipalDao;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.workitem.dto.ExternalCollaborationVO;
import com.aliyun.autowonder.workitem.dto.ExternalPrincipalVO;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalWorkitemViewServiceTest {

    @Test
    void buildsProviderNeutralCollaborationViewFromLinkSnapshot() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalPrincipalDao principalDao = mock(ExternalPrincipalDao.class);
        ExternalWorkitemLinkDO link = new ExternalWorkitemLinkDO();
        link.setId(8L);
        link.setProvider("AONE");
        link.setExternalProjectId("2087214");
        link.setExternalWorkitemId("84877007");
        link.setSourceStatusName("处理中");
        link.setReporterPrincipalId(101L);
        link.setBusinessOwnerPrincipalId(102L);
        link.setPrincipalRelationsJson("""
                [{"source_key":"collaborators","display_name":"协作者","principal_ids":[103]}]
                """);
        link.setSyncStatus("HEALTHY");

        when(linkDao.listByWorkitem(100L, 500L)).thenReturn(List.of(link));
        when(principalDao.listByIds(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream().map(this::principal).toList();
        });

        ExternalCollaborationVO result = new ExternalWorkitemViewService(linkDao, principalDao).find(100L, 500L);

        assertEquals("AONE", result.getProvider());
        assertEquals("84877007", result.getExternalWorkitemId());
        assertEquals("用户-101", result.getReporter().getDisplayName());
        assertEquals("用户-102", result.getBusinessOwner().getDisplayName());
        assertEquals(1, result.getPrincipalRelations().size());
        assertEquals("collaborators", result.getPrincipalRelations().get(0).getSourceKey());
        assertEquals("用户-103", result.getPrincipalRelations().get(0).getPrincipals().get(0).getDisplayName());
    }

    @Test
    void formatsAoneIdentityWhenTheSourceDoesNotProvideADisplayName() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalPrincipalDao principalDao = mock(ExternalPrincipalDao.class);
        ExternalPrincipalDO principal = principal(101L);
        principal.setSubjectId("320687");
        principal.setDisplayName(null);
        when(principalDao.findById(101L)).thenReturn(principal);

        assertEquals("Aone 用户（staffId: 320687）",
                new ExternalWorkitemViewService(linkDao, principalDao).principalName(101L));
    }

    @Test
    void formatsExternalTimelineIdentityWithProviderAndStableSubjectId() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalPrincipalDao principalDao = mock(ExternalPrincipalDao.class);
        ExternalPrincipalDO principal = principal(101L);
        principal.setSubjectId("320687");
        principal.setDisplayName("外部用户");
        when(principalDao.findById(101L)).thenReturn(principal);

        assertEquals("外部用户（Aone · 320687）",
                new ExternalWorkitemViewService(linkDao, principalDao).principalDisplayName(101L));
    }

    @Test
    void resolvesSourceCreatorForImportedWorkitemsInOneBatch() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalPrincipalDao principalDao = mock(ExternalPrincipalDao.class);
        ExternalWorkitemLinkDO aoneLink = new ExternalWorkitemLinkDO();
        aoneLink.setId(1L);
        aoneLink.setWorkitemId(500L);
        aoneLink.setProvider("AONE");
        aoneLink.setReporterPrincipalId(101L);
        ExternalWorkitemLinkDO anotherProviderLink = new ExternalWorkitemLinkDO();
        anotherProviderLink.setId(2L);
        anotherProviderLink.setWorkitemId(500L);
        anotherProviderLink.setProvider("JIRA");
        anotherProviderLink.setReporterPrincipalId(102L);
        ExternalWorkitemLinkDO withoutReporter = new ExternalWorkitemLinkDO();
        withoutReporter.setId(3L);
        withoutReporter.setWorkitemId(501L);
        withoutReporter.setProvider("AONE");

        ExternalPrincipalDO reporter = principal(101L);
        reporter.setSubjectId("440501");
        reporter.setDisplayName("煊童");
        when(principalDao.listByIds(anyCollection())).thenReturn(List.of(reporter));

        Map<Long, ExternalPrincipalVO> result = new ExternalWorkitemViewService(linkDao, principalDao)
                .reportersByWorkitem(List.of(anotherProviderLink, withoutReporter, aoneLink));

        assertEquals("煊童", result.get(500L).getDisplayName());
        assertEquals("440501", result.get(500L).getSubjectId());
        assertFalse(result.containsKey(501L));
    }

    private ExternalPrincipalDO principal(Long id) {
        ExternalPrincipalDO principal = new ExternalPrincipalDO();
        principal.setId(id);
        principal.setProvider("AONE");
        principal.setSubjectId("staff-" + id);
        principal.setDisplayName("用户-" + id);
        return principal;
    }
}
