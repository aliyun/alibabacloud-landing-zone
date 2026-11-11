package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.category.CategoryService;
import com.aliyun.autowonder.category.dto.CategoryVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.skill.SkillDO;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.CreateSkillRequest;
import com.aliyun.autowonder.skill.dto.SkillVO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PrdBrowserServiceTest {
    final CategoryService categories = mock(CategoryService.class);
    final SkillService skills = mock(SkillService.class);
    final SkillDao dao = mock(SkillDao.class);
    final com.aliyun.autowonder.access.WorkspaceDomainGuard guard = mock(com.aliyun.autowonder.access.WorkspaceDomainGuard.class);
    final PrdBrowserService service = new PrdBrowserService(categories, skills, dao, guard);

    void fixture() {
        var category = new CategoryVO(); category.setId(8L); category.setName("browser-use");
        when(categories.list(1L)).thenReturn(List.of(category));
        var skill = new SkillDO(); skill.setId(9L); skill.setTenantId(1L); skill.setType("MCP");
        skill.setName("browser"); skill.setVersion(2);
        skill.setInstallSpec("{\"transport\":\"http\",\"url\":\"http://192.168.1.20:8931/mcp\",\"headers\":{\"Authorization\":{\"kind\":\"secretRef\",\"ref\":\"encrypted-ref\"}}}");
        when(dao.findById(9L)).thenReturn(skill);
        var view = new SkillVO(); view.setId(9L); view.setCategoryId(8L);
        when(skills.get(9L)).thenReturn(view);
        when(skills.create(any(), eq(1L), eq(7L))).thenReturn(view);
    }

    @Test void createsCategorizedHttpMcpUsingExistingSecretEncryption() {
        fixture();
        service.create(new PrdBrowserService.Create("browser", "http://192.168.1.20:8931/mcp", "test-token"), 1, 7);
        var captor = ArgumentCaptor.forClass(CreateSkillRequest.class);
        verify(skills).create(captor.capture(), eq(1L), eq(7L));
        var config = JSON.parseObject(captor.getValue().getInstallSpec());
        var secret = config.getJSONObject("headers").getJSONObject("Authorization");
        assertEquals(Boolean.TRUE, secret.get("secret")); assertEquals("Bearer test-token", secret.get("value"));
        verify(categories).setSkillCategory(9L, 8L, 1L, 7L);
        verify(guard).requireWritableDomains(eq(1L), eq(7L), anyString(), anyString(),
                eq(com.aliyun.autowonder.access.WorkspaceDomain.SKILL), eq(com.aliyun.autowonder.access.WorkspaceDomain.CATEGORY));
    }

    @Test void rejectsMissingSelectionCrossWorkspaceAndWrongCategory() {
        fixture();
        assertThrows(BizException.class, () -> service.validate(null, 1));
        var selection = new PrdBrowserService.Selection(9L, "https://example.com", false);
        assertThrows(BizException.class, () -> service.validate(selection, 2));
        skills.get(9L).setCategoryId(99L);
        assertThrows(BizException.class, () -> service.validate(selection, 1));
    }

    @Test void explicitCodeOnlyNeedsNoBrowserAndStatesLimit() {
        var selected = service.validate(new PrdBrowserService.Selection(9L, "bad", true), 1);
        assertNull(selected.skillId()); assertNull(selected.url());
        assertTrue(service.prompt(selected).contains("未浏览现有界面")); verifyNoInteractions(dao);
    }

    @Test void injectsOnlySelectedBrowserWithSecretReferenceAndKeepsOtherCapabilities() {
        fixture();
        List<Map<String, Object>> capabilities = new ArrayList<>(List.of(Map.of("id", 9L, "type", "MCP"), Map.of("id", 20L, "type", "SKILL")));
        var selection = service.validate(new PrdBrowserService.Selection(9L, "https://example.com/path", false), 1);
        service.addCapability(capabilities, 1, selection);
        assertEquals(2, capabilities.size());
        assertTrue(JSON.toJSONString(capabilities).contains("encrypted-ref"));
        assertFalse(JSON.toJSONString(selection).contains("encrypted-ref"));
        service.addCapability(capabilities, 1, new PrdBrowserService.Selection(null, null, true));
        assertEquals(List.of(Map.of("id", 20L, "type", "SKILL")), capabilities);
    }

    @Test void rejectsCredentialUrlsAndControlCharacters() {
        for (String url : List.of("file:///tmp/page", "http://user:password@example.com", "https://example.com\n"))
            assertThrows(BizException.class, () -> PrdBrowserService.requireUrl(url));
        assertEquals("http://192.168.1.20:8931/mcp", PrdBrowserService.requireUrl("http://192.168.1.20:8931/mcp"));
    }
}
