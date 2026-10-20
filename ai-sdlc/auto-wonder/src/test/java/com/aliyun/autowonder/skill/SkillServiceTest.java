package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.skill.dto.CreateSkillRequest;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.skill.dto.UpdateSkillRequest;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillServiceTest {

    private SkillDao skillDao;
    private AgentSkillDao agentSkillDao;
    private UserDao userDao;
    private SkillService service;

    @BeforeEach
    void setUp() {
        skillDao = mock(SkillDao.class);
        agentSkillDao = mock(AgentSkillDao.class);
        userDao = mock(UserDao.class);
        service = new SkillService(skillDao, agentSkillDao, userDao);
    }

    @Test
    void missingCategoryTablesDoNotHideSkillsAndRecoverWithoutRestart() {
        var categories = mock(com.aliyun.autowonder.category.CategoryService.class);
        service = new SkillService(skillDao, agentSkillDao, userDao, null, categories);
        SkillDO skill = new SkillDO();
        skill.setId(1L);
        skill.setTenantId(1L);
        skill.setName("existing-skill");
        when(skillDao.findById(1L)).thenReturn(skill);
        when(skillDao.list(1L, null, null, false, 0, 20)).thenReturn(List.of(skill));
        when(skillDao.count(1L, null, null, false)).thenReturn(1L);
        var missingRef = new org.springframework.jdbc.BadSqlGrammarException("query", "select", new java.sql.SQLException(
                "Table 'autowonderdev.asset_category_ref' doesn't exist", "42S02", 1146));
        when(categories.mapCategoryIdsByAssets(eq(1L), anyList())).thenThrow(missingRef);
        var page = service.listPage(1L, null, 1, 20);
        assertEquals(1L, page.getTotal());
        assertEquals("existing-skill", page.getList().get(0).getName());
        assertNull(service.get(1L).getCategoryId());

        when(categories.mapCategoryIdsByAssets(eq(1L), anyList())).thenReturn(java.util.Map.of(1L, 2L));
        when(categories.mapCategoryPaths(1L)).thenThrow(new org.springframework.jdbc.BadSqlGrammarException(
                "query", "select", new java.sql.SQLException("Table 'autowonderdev.asset_category' doesn't exist", "42S02", 1146)));
        assertNull(service.get(1L).getCategoryId());
        doReturn(java.util.Map.of(2L, "编码 / 前端")).when(categories).mapCategoryPaths(1L);
        assertEquals("编码 / 前端", service.get(1L).getCategoryPath());
    }

    @Test
    void unrelatedDatabaseErrorsAreNotSuppressed() {
        var categories = mock(com.aliyun.autowonder.category.CategoryService.class);
        service = new SkillService(skillDao, agentSkillDao, userDao, null, categories);
        SkillDO skill = new SkillDO();
        skill.setId(1L);
        skill.setTenantId(1L);
        when(skillDao.findById(1L)).thenReturn(skill);
        for (var sql : List.of(
                new java.sql.SQLException("Table 'autowonderdev.skill' doesn't exist", "42S02", 1146),
                new java.sql.SQLException("Unknown column 'category_id'", "42S22", 1054),
                new java.sql.SQLException("Connection failed", "08001", 0))) {
            var error = new org.springframework.jdbc.BadSqlGrammarException("query", "select", sql);
            when(categories.mapCategoryIdsByAssets(eq(1L), anyList())).thenThrow(error);
            assertSame(error, assertThrows(org.springframework.dao.DataAccessException.class, () -> service.get(1L)));
        }
    }

    @Test
    void createSuccess() {
        when(skillDao.findByTypeAndName(1L, "MCP", "my-server")).thenReturn(null);

        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("MCP");
        req.setName("my-server");
        req.setInstallSpec("{\"transport\":\"stdio\",\"command\":\"node\"}");
        req.setDescription("desc");

        SkillVO vo = service.create(req, 1L, 2L);
        assertEquals("MCP", vo.getType());
        assertEquals("my-server", vo.getName());
        verify(skillDao).insert(any());
    }

    @Test
    void createJsonQuotesPlainInstallSpecBeforeInsert() {
        when(skillDao.findByTypeAndName(1L, "SKILL", "alibabacloud-find-skills")).thenReturn(null);

        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("SKILL");
        req.setName("alibabacloud-find-skills");
        req.setInstallSpec("npx");

        service.create(req, 1L, 2L);

        ArgumentCaptor<SkillDO> captor = ArgumentCaptor.forClass(SkillDO.class);
        verify(skillDao).insert(captor.capture());
        assertEquals("\"npx\"", captor.getValue().getInstallSpec());
    }

    @Test
    void getReturnsPlainInstallSpecWhenStoredAsJsonString() {
        SkillDO s = new SkillDO();
        s.setId(1L);
        s.setType("SKILL");
        s.setName("alibabacloud-find-skills");
        s.setInstallSpec("\"npx\"");
        when(skillDao.findById(1L)).thenReturn(s);

        SkillVO vo = service.get(1L);

        assertEquals("npx", vo.getInstallSpec());
    }

    @Test
    void getReturnsModifiedMetadataAndModifierName() {
        Date modifiedAt = new Date(1783900000000L);
        SkillDO s = new SkillDO();
        s.setId(1L);
        s.setType("MCP");
        s.setName("github-mcp");
        s.setInstallSpec("\"npx @anthropic/mcp-server-github\"");
        s.setGmtModified(modifiedAt);
        s.setCreatorId(10L);
        s.setModifierId(20L);
        UserDO modifier = new UserDO();
        modifier.setId(20L);
        modifier.setNickname("蔡何");
        modifier.setUsername("caihe");
        when(skillDao.findById(1L)).thenReturn(s);
        when(userDao.findByIdIncludingDeleted(20L)).thenReturn(modifier);

        SkillVO vo = service.get(1L);

        assertEquals(modifiedAt, vo.getGmtModified());
        assertEquals(20L, vo.getModifierId());
        assertEquals("蔡何", vo.getModifierName());
    }

    @Test
    void createDuplicateNameThrows() {
        SkillDO existing = new SkillDO();
        existing.setId(50L);
        when(skillDao.findByTypeAndName(1L, "MCP", "dup")).thenReturn(existing);

        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("MCP");
        req.setName("dup");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));
        assertEquals(ErrorCode.SKILL_DUPLICATE_NAME.getCode(), ex.getCode());
        verify(skillDao, never()).releaseSoftDeletedName(anyLong(), anyString(), anyString());
        verify(skillDao, never()).insert(any());
    }

    @Test
    void createReleasesSoftDeletedNameBeforeInsertUsingTrimmedName() {
        when(skillDao.findByTypeAndName(1L, "MCP", "revived")).thenReturn(null);

        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("MCP");
        req.setName("  revived  ");

        service.create(req, 1L, 2L);

        InOrder inOrder = inOrder(skillDao);
        inOrder.verify(skillDao).findByTypeAndName(1L, "MCP", "revived");
        inOrder.verify(skillDao).releaseSoftDeletedName(1L, "MCP", "revived");
        inOrder.verify(skillDao).insert(argThat(row -> "revived".equals(row.getName())));
    }

    @Test
    void createFromPackageReferenceReleasesSoftDeletedNameBeforeInsert() {
        when(skillDao.findByTypeAndName(1L, "SKILL", "packaged")).thenReturn(null);

        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("SKILL");
        req.setName("packaged");
        service.createFromPackageReference(req,
                new SkillService.PackageReference("oss://skills/packaged.zip", "packaged.zip", 12L, "md5"), 1L, 2L);

        InOrder inOrder = inOrder(skillDao);
        inOrder.verify(skillDao).releaseSoftDeletedName(1L, "SKILL", "packaged");
        inOrder.verify(skillDao).insert(argThat(row -> "packaged".equals(row.getName())));
    }

    @Test
    void createFromPackageReferenceDuplicateNameThrowsAndDoesNotRelease() {
        SkillDO existing = new SkillDO();
        existing.setId(99L);
        existing.setName("dup");
        // 只按 trim 后的 "dup" 打桩：若判重仍用未 trim 原值则打桩不命中，用例会因未抛异常而失败
        when(skillDao.findByTypeAndName(1L, "SKILL", "dup")).thenReturn(existing);

        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("SKILL");
        req.setName("  dup  ");

        BizException ex = assertThrows(BizException.class, () -> service.createFromPackageReference(req,
                new SkillService.PackageReference("oss://skills/dup.zip", "dup.zip", 12L, "md5"), 1L, 2L));

        assertEquals(ErrorCode.SKILL_DUPLICATE_NAME.getCode(), ex.getCode());
        verify(skillDao).findByTypeAndName(1L, "SKILL", "dup");
        // 存在在用同名技能时绝不能释放名称槽位（会改写在用行）、绝不能写入
        verify(skillDao, never()).releaseSoftDeletedName(anyLong(), anyString(), anyString());
        verify(skillDao, never()).insert(any());
    }

    @Test
    void createPropagatesInsertFailureAfterReleaseAndRetrySucceeds() {
        when(skillDao.findByTypeAndName(1L, "MCP", "flaky")).thenReturn(null);
        // 首次释放命中遗留软删除行返回 1，重试时该行已墓碑化返回 0
        when(skillDao.releaseSoftDeletedName(1L, "MCP", "flaky")).thenReturn(1).thenReturn(0);
        doThrow(new RuntimeException("insert failed")).doNothing().when(skillDao).insert(any());

        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("MCP");
        req.setName("flaky");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.create(req, 1L, 2L));
        assertEquals("insert failed", ex.getMessage());

        InOrder inOrder = inOrder(skillDao);
        inOrder.verify(skillDao).releaseSoftDeletedName(1L, "MCP", "flaky");
        inOrder.verify(skillDao).insert(any());

        // 释放与 INSERT 非原子（create 未标注 @Transactional），但槽位已释放且释放幂等 → 重试即可成功
        assertNotNull(service.create(req, 1L, 2L));
        verify(skillDao, times(2)).releaseSoftDeletedName(1L, "MCP", "flaky");
        verify(skillDao, times(2)).insert(any());
    }

    @Test
    void createMissingNameThrows() {
        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("MCP");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));
        assertEquals(ErrorCode.SKILL_NAME_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void createMissingTypeThrows() {
        CreateSkillRequest req = new CreateSkillRequest();
        req.setName("x");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));
        assertEquals(ErrorCode.SKILL_TYPE_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void genericCreateRejectsPluginWithoutPackageMetadata() {
        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("PLUGIN");
        req.setName("team-tools");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(skillDao, never()).insert(any());
    }

    @Test
    void genericCreateRejectsHookWithoutValidatedPackage() {
        CreateSkillRequest req = new CreateSkillRequest();
        req.setType("HOOK");
        req.setName("before-step");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(skillDao, never()).insert(any());
    }

    @Test
    void genericUpdateRejectsExistingPlugin() {
        SkillDO plugin = new SkillDO();
        plugin.setId(9L);
        plugin.setType("PLUGIN");
        when(skillDao.findById(9L)).thenReturn(plugin);

        BizException ex = assertThrows(BizException.class,
                () -> service.update(9L, new UpdateSkillRequest(), 1L, 2L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(skillDao, never()).update(anyLong(), anyLong(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void getNotFoundThrows() {
        when(skillDao.findById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> service.get(99L));
        assertEquals(ErrorCode.SKILL_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void deleteInUseThrows() {
        SkillDO s = new SkillDO();
        s.setId(1L);
        s.setTenantId(1L);
        s.setVersion(0);
        when(skillDao.findByIdForUpdate(1L)).thenReturn(s);
        when(agentSkillDao.countBySkillId(1L, 1L)).thenReturn(2);

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L, 1L, 2L));
        assertEquals(ErrorCode.SKILL_DELETE_IN_USE.getCode(), ex.getCode());
    }

    @Test
    void deleteSuccess() {
        SkillDO s = new SkillDO();
        s.setId(1L);
        s.setTenantId(1L);
        s.setVersion(0);
        when(skillDao.findByIdForUpdate(1L)).thenReturn(s);
        when(agentSkillDao.countBySkillId(1L, 1L)).thenReturn(0);
        when(skillDao.softDelete(1L, 1L, 0, 2L)).thenReturn(1);

        service.delete(1L, 1L, 2L);
        verify(skillDao).softDelete(1L, 1L, 0, 2L);
    }

    @Test
    void deleteRemovesCategoryReference() {
        var categories = mock(com.aliyun.autowonder.category.CategoryService.class);
        var serviceWithCategories = new SkillService(skillDao, agentSkillDao, userDao, null, categories);
        SkillDO skill = new SkillDO();
        skill.setId(1L);
        skill.setTenantId(1L);
        skill.setVersion(0);
        when(skillDao.findByIdForUpdate(1L)).thenReturn(skill);
        when(skillDao.softDelete(1L, 1L, 0, 2L)).thenReturn(1);
        serviceWithCategories.delete(1L, 1L, 2L);
        verify(categories).removeSkillCategory(1L, 1L);
    }

    @Test
    void updateVersionConflictThrows() {
        SkillDO s = new SkillDO();
        s.setId(1L);
        s.setTenantId(1L);
        s.setVersion(3);
        s.setName("old");
        s.setType("MCP");
        s.setInstallSpec("{}");
        s.setDescription("d");
        when(skillDao.findById(1L)).thenReturn(s);
        when(skillDao.update(eq(1L), eq(1L), any(), any(), any(), any(), eq(3), eq(2L))).thenReturn(0);

        UpdateSkillRequest req = new UpdateSkillRequest();
        req.setName("new");
        BizException ex = assertThrows(BizException.class, () -> service.update(1L, req, 1L, 2L));
        assertEquals(ErrorCode.SKILL_VERSION_CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void updateJsonQuotesPlainInstallSpecBeforePersisting() {
        SkillDO s = new SkillDO();
        s.setId(1L);
        s.setTenantId(1L);
        s.setVersion(3);
        s.setName("old");
        s.setType("SKILL");
        s.setInstallSpec("\"old\"");
        s.setDescription("d");
        when(skillDao.findById(1L)).thenReturn(s);
        when(skillDao.update(eq(1L), eq(1L), eq("old"), eq("SKILL"), eq("\"npx\""), eq("d"), eq(3), eq(2L))).thenReturn(1);

        UpdateSkillRequest req = new UpdateSkillRequest();
        req.setInstallSpec("npx");
        service.update(1L, req, 1L, 2L);

        verify(skillDao).update(eq(1L), eq(1L), eq("old"), eq("SKILL"), eq("\"npx\""), eq("d"), eq(3), eq(2L));
    }

    @Test
    void updateFromPackageReferenceUsesOneVersionedWrite() {
        SkillDO s = new SkillDO();
        s.setId(1L);
        s.setTenantId(1L);
        s.setVersion(3);
        s.setName("old");
        s.setType("SKILL");
        s.setInstallSpec("\"old\"");
        s.setDescription("d");
        when(skillDao.findById(1L)).thenReturn(s);
        when(skillDao.updatePackage(eq(1L), eq(1L), eq("SKILL"), eq("\"new\""), eq("old"), eq("d"),
                eq("OSS_ZIP"), eq("oss://skills/new.zip"), isNull(), isNull(), eq("abc"), eq(3), eq(2L)))
                .thenReturn(1);

        UpdateSkillRequest req = new UpdateSkillRequest();
        req.setInstallSpec("new");
        service.updateFromPackageReference(1L, req,
                new SkillService.PackageReference("oss://skills/new.zip", null, null, "abc"), 1L, 2L);

        verify(skillDao, never()).update(anyLong(), anyLong(), any(), any(), any(), any(), anyInt(), anyLong());
        verify(skillDao).updatePackage(eq(1L), eq(1L), eq("SKILL"), eq("\"new\""), eq("old"), eq("d"),
                eq("OSS_ZIP"), eq("oss://skills/new.zip"), isNull(), isNull(), eq("abc"), eq(3), eq(2L));
    }

    @Test
    void createTreatsStringPrivateMarkerAsSecretForHeadersAndEnv() {
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(secretCrypto.encrypt("header-secret")).thenReturn("kc:v1:header");
        when(secretCrypto.encrypt("env-secret")).thenReturn("kc:v1:env");
        service = new SkillService(skillDao, agentSkillDao, userDao, secretCrypto);
        when(skillDao.findByTypeAndName(1L, "MCP", "secure-http")).thenReturn(null);
        when(skillDao.findByTypeAndName(1L, "MCP", "secure-stdio")).thenReturn(null);

        CreateSkillRequest http = new CreateSkillRequest();
        http.setType("MCP");
        http.setName("secure-http");
        http.setInstallSpec("{\"transport\":\"http\",\"url\":\"https://mcp.example.test\",\"headers\":{\"Authorization\":{\"secret\":\"true\",\"value\":\"header-secret\"}}}");
        service.create(http, 1L, 2L);

        CreateSkillRequest stdio = new CreateSkillRequest();
        stdio.setType("MCP");
        stdio.setName("secure-stdio");
        stdio.setInstallSpec("{\"transport\":\"stdio\",\"command\":\"server\",\"env\":{\"TOKEN\":{\"secret\":\"true\",\"value\":\"env-secret\"}}}");
        service.create(stdio, 1L, 2L);

        ArgumentCaptor<SkillDO> stored = ArgumentCaptor.forClass(SkillDO.class);
        verify(skillDao, times(2)).insert(stored.capture());
        assertEquals("kc:v1:header", JSON.parseObject(stored.getAllValues().get(0).getInstallSpec())
                .getJSONObject("headers").getJSONObject("Authorization").getString("ref"));
        assertEquals("kc:v1:env", JSON.parseObject(stored.getAllValues().get(1).getInstallSpec())
                .getJSONObject("env").getJSONObject("TOKEN").getString("ref"));
    }

    @Test
    void listPassesTenantTypeAndComputedOffsetToDao() {
        SkillDO row = new SkillDO();
        row.setId(1L);
        row.setType("MCP");
        row.setName("github-mcp");
        row.setInstallSpec("\"npx\"");
        // page=3,size=10 -> offset=(3-1)*10=20；tenantId 必须透传给 DAO 做租户隔离
        when(skillDao.list(7L, "MCP", null, false, 20, 10)).thenReturn(List.of(row));

        List<SkillVO> result = service.list(7L, "MCP", 3, 10);

        assertEquals(1, result.size());
        assertEquals("github-mcp", result.get(0).getName());
        verify(skillDao).list(7L, "MCP", null, false, 20, 10);
    }

    @Test
    void listNormalizesPageAndSizeBounds() {
        when(skillDao.list(7L, null, null, false, 0, 100)).thenReturn(List.of());

        // page<1 归一到第 1 页(offset 0)，size 超过上限被夹到 100
        service.list(7L, null, 0, 1000);

        verify(skillDao).list(7L, null, null, false, 0, 100);
    }

    @Test
    void listPageReturnsTenantScopedTotalFromCountNotListLength() {
        SkillDO a = new SkillDO();
        a.setId(1L);
        a.setType("SKILL");
        a.setName("a");
        SkillDO b = new SkillDO();
        b.setId(2L);
        b.setType("SKILL");
        b.setName("b");
        // 当前页只有 2 行，但租户内真实总数是 45：total 必须取自 count，而不是 list.size()
        when(skillDao.list(7L, "SKILL", null, false, 0, 20)).thenReturn(List.of(a, b));
        when(skillDao.count(7L, "SKILL", null, false)).thenReturn(45L);

        PageResult<SkillVO> page = service.listPage(7L, "SKILL", 1, 20);

        assertEquals(45L, page.getTotal());
        assertEquals(2, page.getList().size());
        assertEquals(1, page.getPageNum());
        assertEquals(20, page.getPageSize());
        // count 同样按 tenantId+type 收敛，避免跨租户串数
        verify(skillDao).count(7L, "SKILL", null, false);
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> ownedConfigCases() {
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("MCP", "[]", "MCP 配置必须是 JSON 对象", "MCP configuration must be a JSON object"),
            org.junit.jupiter.params.provider.Arguments.of("MCP", "{\"transport\":\"other\"}", "MCP 连接方式仅支持 HTTP、SSE 或 stdio", "MCP transport must be HTTP, SSE or stdio"),
            org.junit.jupiter.params.provider.Arguments.of("MCP", "{\"transport\":\"stdio\"}", "MCP 本地命令不能为空", "The local MCP command cannot be empty"),
            org.junit.jupiter.params.provider.Arguments.of("MCP", "{\"transport\":\"stdio\",\"command\":\"x\",\"headers\":{}}", "stdio MCP 不支持请求头或超时配置", "stdio MCP does not support headers or timeout configuration"),
            org.junit.jupiter.params.provider.Arguments.of("MCP", "{}", "MCP 地址必须是 HTTP/HTTPS URL", "The MCP address must be an HTTP/HTTPS URL"),
            org.junit.jupiter.params.provider.Arguments.of("MCP", "{\"url\":\"https://raw\",\"timeoutSeconds\":601}", "MCP 超时时间必须在 1 到 600 秒之间", "MCP timeout must be between 1 and 600 seconds"),
            org.junit.jupiter.params.provider.Arguments.of("MCP", "{\"url\":\"https://raw\",\"headers\":[]}", "MCP Headers 必须是键值对象", "MCP Headers must be a key-value object"),
            org.junit.jupiter.params.provider.Arguments.of("MCP", "{\"url\":\"https://raw\",\"headers\":{\"<raw>\":\"x\"}}", "MCP Headers 名称不合法: <raw>", "MCP Headers has an invalid name: <raw>"),
            org.junit.jupiter.params.provider.Arguments.of("MCP", "{\"url\":\"https://raw\",\"headers\":{\"x\":\"a\",\"X\":\"b\"}}", "MCP Headers 名称不能重复: X", "MCP Headers has a duplicate name: X"),
            org.junit.jupiter.params.provider.Arguments.of("MCP", "{\"url\":\"https://raw\",\"headers\":{\"X\":\"a\\nb\"}}", "MCP Headers 值不合法: X", "MCP Headers has an invalid value: X"),
            org.junit.jupiter.params.provider.Arguments.of("MCP", "{\"transport\":\"stdio\",\"command\":\"x\",\"env\":[]}", "MCP Env 必须是键值对象", "MCP Env must be a key-value object"),
            org.junit.jupiter.params.provider.Arguments.of("MCP", "{\"url\":\"https://raw\",\"headers\":{\"X\":{\"secret\":true}}}", "私密配置首次保存时必须填写值", "A value is required when first saving private configuration"),
            org.junit.jupiter.params.provider.Arguments.of("HOOK", "{}", "插件和 Runtime Hook 必须通过安装包入口配置", "Plugins and Runtime Hooks must be configured through package installation"),
            org.junit.jupiter.params.provider.Arguments.of("PLUGIN", "{}", "插件和 Runtime Hook 必须通过安装包入口配置", "Plugins and Runtime Hooks must be configured through package installation")
        );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("ownedConfigCases")
    void ownedConfigurationErrorsRenderAtBoundaryWithRawDiagnostics(String type, String spec, String raw, String en) {
        var request = new CreateSkillRequest(); request.setName("<raw name>"); request.setType(type); request.setInstallSpec(spec);
        BizException error = assertThrows(BizException.class, () -> service.create(request, 100L, 1L));
        assertEquals(BizException.class, error.getClass()); assertEquals(ErrorCode.PARAM_INVALID.getCode(), error.getCode());
        assertEquals(raw, error.getMessage()); assertNull(error.getCause()); assertEquals(spec, request.getInstallSpec());
        verify(skillDao, never()).insert(any());
        assertEquals(raw, com.aliyun.autowonder.common.i18n.SystemMessages.render(error.getMessageKey(), error.getMessageArgs(), raw, java.util.Locale.CHINA));
        assertEquals(en, com.aliyun.autowonder.common.i18n.SystemMessages.render(error.getMessageKey(), error.getMessageArgs(), raw, java.util.Locale.US));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "blank", "raw-name"})
    void modifierOriginSurvivesSharedListCacheWithoutGuessingNames(String source) {
        SkillDO one = new SkillDO(); one.setId(1L); one.setTenantId(100L); one.setModifierId(7L);
        SkillDO two = new SkillDO(); two.setId(2L); two.setTenantId(100L); two.setModifierId(7L);
        when(skillDao.list(100L, null, null, false, 0, 20)).thenReturn(List.of(one, two));
        if (!source.equals("missing")) {
            UserDO user = new UserDO(); user.setId(7L);
            if (source.equals("raw-name")) user.setNickname("用户 #7");
            when(userDao.findByIdIncludingDeleted(7L)).thenReturn(user);
        }
        var result = service.list(100L, null, 1, 20);
        verify(userDao, times(1)).findByIdIncludingDeleted(7L);
        for (var skill : result) {
            var json = JSON.parseObject(JSON.toJSONString(skill));
            assertEquals("用户 #7", skill.getModifierName()); assertEquals(7L, skill.getModifierId());
            assertEquals(source.equals("raw-name") ? null : "USER_ID_FALLBACK", json.getString("modifierNameKind"));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"filter-conflict","filter-id","headers-limit","env-limit"})
    void filterAndMapLimitsKeepOriginalOrderAndDiagnostics(String branch) {
        BizException error;
        if(branch.startsWith("filter")) error=assertThrows(BizException.class,()->service.listPage(100L,null,branch.equals("filter-id") ? 0L : 1L,false,branch.equals("filter-conflict"),1,20));
        else {
            var values=new java.util.LinkedHashMap<String,String>();for(int i=0;i<33;i++)values.put("RAW_"+i,"<raw>");
            var config=new java.util.LinkedHashMap<String,Object>();
            if(branch.equals("env-limit")){config.put("transport","stdio");config.put("command","raw-command");config.put("env",values);}
            else{config.put("url","https://raw");config.put("headers",values);}
            var req=new CreateSkillRequest();req.setType("MCP");req.setName("raw");req.setInstallSpec(JSON.toJSONString(config));
            error=assertThrows(BizException.class,()->service.create(req,100L,1L));
        }
        String raw=switch(branch){case "filter-conflict"->"分类筛选与未分类筛选互斥，不能同时使用";case "filter-id"->"分类 ID 不合法";case "headers-limit"->"MCP Headers 最多支持 32 项";default->"MCP Env 最多支持 32 项";};
        String en=switch(branch){case "filter-conflict"->"Category and uncategorized filters cannot be used together";case "filter-id"->"The category ID is invalid";case "headers-limit"->"MCP Headers supports at most 32 entries";default->"MCP Env supports at most 32 entries";};
        assertEquals(raw,error.getMessage());assertEquals(BizException.class,error.getClass());assertNull(error.getCause());
        assertEquals(en,com.aliyun.autowonder.common.i18n.SystemMessages.render(error.getMessageKey(),error.getMessageArgs(),raw,java.util.Locale.US));
        verify(skillDao,never()).insert(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"missing-keycenter","blank-reference"})
    void swallowedKeyCenterDiagnosticsStillReturnOriginalSpecWithoutCreatingHumanError(String branch) {
        var keyCenter=branch.equals("missing-keycenter") ? null : mock(SecretCrypto.class);
        if(keyCenter!=null) when(keyCenter.encrypt("<secret>")).thenReturn(" ");
        service=new SkillService(skillDao,agentSkillDao,userDao,keyCenter,null);
        String raw="{\"url\":\"https://raw\",\"headers\":{\"X\":{\"secret\":true,\"value\":\"<secret>\"}}}";
        var req=new CreateSkillRequest();req.setName("raw");req.setType("MCP");req.setInstallSpec(raw);
        var captured=org.mockito.ArgumentCaptor.forClass(SkillDO.class);
        service.create(req,100L,1L);verify(skillDao).insert(captured.capture());
        assertEquals(JSON.toJSONString(raw),captured.getValue().getInstallSpec());assertEquals(raw,req.getInstallSpec());
    }

}
