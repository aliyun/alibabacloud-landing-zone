package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.memory.store.dto.MemoryMutationRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryTopicRequest;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryHumanI18nTest {
    record Failure(Executable operation, String diagnostic, String zh, String en) {}
    @AfterEach void cleanup() { LocaleContextHolder.resetLocaleContext(); com.aliyun.autowonder.context.AutoWonderContext.destroy(); }

    static Stream<Failure> validations() {
        return Stream.of(
            new Failure(() -> MemoryPathValidator.requireSafe(null), "unsafe memory path: path is blank", "不安全的记忆路径：路径不能为空", "Unsafe memory path: Path must not be blank"),
            new Failure(() -> MemoryPathValidator.requireSafe("x".repeat(513)), "unsafe memory path: path is too long", "不安全的记忆路径：路径过长", "Unsafe memory path: Path is too long"),
            new Failure(() -> MemoryPathValidator.requireSafe("/a.md"), "unsafe memory path: absolute paths are not allowed", "不安全的记忆路径：不允许绝对路径", "Unsafe memory path: Absolute paths are not allowed"),
            new Failure(() -> MemoryPathValidator.requireSafe("a\\b.md"), "unsafe memory path: backslashes are not allowed", "不安全的记忆路径：不允许反斜杠", "Unsafe memory path: Backslashes are not allowed"),
            new Failure(() -> MemoryPathValidator.requireSafe("%2e.md"), "unsafe memory path: encoded traversal is not allowed", "不安全的记忆路径：不允许编码的路径穿越", "Unsafe memory path: Encoded traversal is not allowed"),
            new Failure(() -> MemoryPathValidator.requireSafe("a\n.md"), "unsafe memory path: control characters are not allowed", "不安全的记忆路径：不允许控制字符", "Unsafe memory path: Control characters are not allowed"),
            new Failure(() -> MemoryPathValidator.requireSafe("../a.md"), "unsafe memory path: empty or traversal segments are not allowed", "不安全的记忆路径：不允许空路径段或路径穿越", "Unsafe memory path: Empty or traversal segments are not allowed"),
            new Failure(() -> MemoryPathValidator.requireSafe(".a.md"), "unsafe memory path: hidden path segments are not allowed", "不安全的记忆路径：不允许隐藏路径段", "Unsafe memory path: Hidden path segments are not allowed"),
            new Failure(() -> MemoryPathValidator.requireSafe("con.md"), "unsafe memory path: platform-reserved path segment", "不安全的记忆路径：路径段为平台保留名称", "Unsafe memory path: Path segment is reserved by the platform"),
            new Failure(() -> MemoryPathValidator.requireSafe("a.txt"), "unsafe memory path: autonomous memory documents must be Markdown", "不安全的记忆路径：自主记忆文档必须为 Markdown", "Unsafe memory path: Autonomous memory documents must be Markdown"),
            new Failure(() -> MemoryDocumentValidator.requireValid("a.md", null), "memory content is required", "记忆内容不能为空", "Memory content is required"),
            new Failure(() -> MemoryDocumentValidator.requireValid("a.md", "x".repeat(1048577)), "memory document exceeds 1048576 bytes", "记忆文档超过 1,048,576 字节", "Memory document exceeds 1,048,576 bytes"),
            new Failure(() -> MemoryDocumentValidator.requireValid("a.md", "\ud800"), "memory content is not valid Unicode", "记忆内容不是有效的 Unicode", "Memory content is not valid Unicode"),
            new Failure(() -> MemoryDocumentValidator.requireCanonicalTopic("a.md", "body"), "memory topic requires YAML frontmatter", "记忆主题必须包含 YAML 元信息", "Memory topic requires YAML frontmatter"),
            new Failure(() -> MemoryDocumentValidator.requireCanonicalTopic("a.md", "---\ntype: user"), "memory topic frontmatter is not closed", "记忆主题的元信息未闭合", "Memory topic frontmatter is not closed"),
            new Failure(() -> MemoryDocumentValidator.requireCanonicalTopic("a.md", "---\ntype: user\nmetadata:\n  type: feedback\n---\nbody"), "memory topic type declarations conflict", "记忆主题的类型声明冲突", "Memory topic type declarations conflict"),
            new Failure(() -> MemoryDocumentValidator.requireCanonicalTopic("a.md", "---\ntype: unknown\n---\nbody"), "memory topic type is invalid", "记忆主题的类型无效", "Memory topic type is invalid"),
            new Failure(() -> MemoryTopicFormat.parse("---\ntype: user"), "memory frontmatter is not closed", "记忆元信息未闭合", "Memory frontmatter is not closed"),
            new Failure(() -> MemoryTopicFormat.parse("---\nscalar\n---\nbody"), "memory frontmatter must be a mapping", "记忆元信息必须为映射", "Memory frontmatter must be a mapping"),
            new Failure(() -> MemoryTopicFormat.render("a", "unknown", "title", "desc", "body", Map.of()), "invalid memory type", "记忆类型无效", "Invalid memory type"),
            new Failure(() -> MemoryTopicFormat.render("a", "user", "", "desc", "body", Map.of()), "title, description and body are required", "标题、适用说明和正文不能为空", "Title, description and body are required"),
            new Failure(() -> MemoryTopicFormat.upsertIndex("", "x".repeat(290)+".md", "t", "d"), "memory path is too long for index entry", "记忆路径过长，无法添加索引条目", "Memory path is too long for an index entry")
        );
    }

    @ParameterizedTest @MethodSource("validations")
    void rendersOwnedValidationInBothLocalesWithoutChangingDiagnostics(Failure failure) throws Throwable {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, failure.operation());
        assertEquals(failure.diagnostic(), error.getMessage());
        assertEquals(failure.diagnostic(), error.getLocalizedMessage());
        assertNull(error.getCause());
        assertDisplay(error, failure.zh(), failure.en());
    }

    private static void assertDisplay(IllegalArgumentException error, String zh, String en) {
        var global = new GlobalExceptionHandler();
        var topic = new MemoryTopicController(mock(MemoryTopicService.class));
        for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
            LocaleContextHolder.setLocale(locale);
            String expected = Locale.US.equals(locale) ? en : zh;
            var response = global.handleIllegalArg(error);
            assertEquals("10001", response.getCode()); assertNull(response.getData());
            assertEquals(expected, response.getMessage());
            var local = topic.invalid(error);
            assertEquals(400, local.getStatusCode().value());
            assertEquals("10001", local.getBody().getCode());
            assertEquals(expected, local.getBody().getMessage());
        }
    }

    @Test void localConflictRefreshIsOwnedButSameLookingOrdinaryValidationIsRaw() {
        var controller = new MemoryTopicController(mock(MemoryTopicService.class));
        String diagnostic = "<b>记忆已被修改，请刷新后重试</b>";
        var error = new MemoryDocumentService.MemoryConflictException(diagnostic);
        for (Locale locale : List.of(Locale.SIMPLIFIED_CHINESE, Locale.US)) {
            LocaleContextHolder.setLocale(locale);
            var response = controller.conflict(error);
            assertEquals(409, response.getStatusCode().value());
            assertEquals("10409", response.getBody().getCode());
            assertEquals(Locale.US.equals(locale) ? "Memory has changed. Refresh and try again." : "记忆已被修改，请刷新后重试", response.getBody().getMessage());
            assertEquals(diagnostic, error.getMessage());
            assertEquals(diagnostic, controller.invalid(new IllegalArgumentException(diagnostic)).getBody().getMessage());
        }
    }

    static Stream<Failure> producers() {
        var stores = mock(MemoryStoreDao.class); var documents = mock(MemoryDocumentDao.class);
        var changes = mock(MemoryChangeDao.class); var acls = mock(MemoryStoreAclDao.class);
        var mutations = new MemoryDocumentService(stores, documents, changes);
        var request = MemoryMutationRequest.create("a.md", "body", "key");
        var actor = new MemoryDocumentService.Actor("USER", "9", 9L, null, null);
        var application = new MemoryStoreApplicationService(stores, documents, changes, acls,
                mock(SquadMemberDao.class), new MemoryStoreAccessService(), mutations,
                new MemorySnapshotService(), mock(MemoryStoreBootstrapService.class));
        var topics = new MemoryTopicService(stores, documents, changes, acls, mutations,
                new MemoryStoreAccessService(), mock(AgentDao.class), mock(SquadDao.class));
        var imports = mock(MemoryImportDao.class); var admin = new MemoryImportAdminService(imports);
        return Stream.of(
            new Failure(() -> mutations.mutate(7, 11, null, actor), "operation and idempotency key are required",
                "操作和幂等键不能为空", "Operation and idempotency key are required"),
            new Failure(() -> mutations.mutate(7, 11, request, null), "memory mutation actor is required",
                "记忆变更的执行者不能为空", "Memory mutation actor is required"),
            new Failure(() -> mutations.mutate(7, 11, request, actor), "active memory store not found",
                "未找到有效的记忆库", "Active memory store not found"),
            new Failure(() -> application.createStore(7, 9, true, null), "supported memory scope is required",
                "必须指定受支持的记忆范围", "A supported memory scope is required"),
            new Failure(() -> application.documentsForUser(7, 9, true, 11), "memory store not found",
                "未找到记忆库", "Memory store not found"),
            new Failure(() -> topics.createForUser(7, 9, true, "AGENT", 42, null), "memory topic required",
                "记忆主题不能为空", "Memory topic is required"),
            new Failure(() -> topics.createForUser(7, 9, true, "AGENT", 42,
                new MemoryTopicRequest(null, "user", "title", "desc", "body", null, "")), "invalid idempotency key",
                "幂等键无效", "Invalid idempotency key"),
            new Failure(() -> admin.changeStatus(7, 21, "ACTIVE", 1), "memory import source not found",
                "未找到记忆导入来源", "Memory import source not found")
        );
    }
    @ParameterizedTest @MethodSource("producers")
    void actualServiceFamiliesRenderWithoutChangingTheirRawException(Failure failure) {
        var error = assertThrows(IllegalArgumentException.class, failure.operation());
        assertEquals(failure.diagnostic(), error.getMessage()); assertNull(error.getCause());
        assertDisplay(error, failure.zh(), failure.en());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"---\ntype: user", "---\na: [\n---\n<b>raw</b>"})
    void parseWarningOriginIsAssignedOnlyByTheActualMalformedSource(String malformed) throws Exception {
        var json = new ObjectMapper();
        {
            var document = new MemoryDocumentDO(); document.setPath("raw.md"); document.setContentMd(malformed);
            MemoryDirectoryService.describe(document);
            assertEquals("元信息无法解析，可在文件详情中查看原文", document.getDescription());
            assertEquals(malformed, document.getContentMd()); assertEquals(malformed, document.getBody());
            assertEquals("raw.md", document.getPath());
            assertEquals("METADATA_PARSE_WARNING", json.valueToTree(document).path("descriptionKind").asText());
        }
        var authored = new MemoryDocumentDO(); authored.setPath("authored.md");
        authored.setContentMd("---\ndescription: 元信息无法解析，可在文件详情中查看原文\n---\n<b>raw body</b>");
        MemoryDirectoryService.describe(authored);
        assertEquals("元信息无法解析，可在文件详情中查看原文", authored.getDescription());
        assertFalse(json.valueToTree(authored).has("descriptionKind"));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"MEMORY.md", "valid.md", "plain.md", "empty.md"})
    void reusedDocumentClearsParseWarningOnEveryNonFailurePath(String path) throws Exception {
        var document = new MemoryDocumentDO(); document.setPath("broken.md"); document.setContentMd("---\ntype: user");
        MemoryDirectoryService.describe(document);
        assertEquals("METADATA_PARSE_WARNING", document.getDescriptionKind());
        String raw = switch (path) {
            case "valid.md" -> "---\ndescription: 元信息无法解析，可在文件详情中查看原文\n---\n<b>authored</b>";
            case "empty.md" -> "";
            default -> "<b>raw unchanged</b>";
        };
        document.setPath(path); document.setContentMd(raw);
        MemoryDirectoryService.describe(document);
        assertFalse(new ObjectMapper().valueToTree(document).has("descriptionKind"));
        assertEquals(raw, document.getContentMd()); assertEquals(path, document.getPath());
        if ("valid.md".equals(path)) assertEquals("元信息无法解析，可在文件详情中查看原文", document.getDescription());
    }

    @Test void invalidIndexCarriesOnlyStableRawViolationsAndPreservesMachineClassifier() {
        var stores = mock(MemoryStoreDao.class); var documents = mock(MemoryDocumentDao.class);
        var changes = mock(MemoryChangeDao.class);
        var store = new MemoryStoreDO(); store.setId(11L); store.setTenantId(7L); store.setScope("AGENT"); store.setStatus("ACTIVE");
        when(stores.findById(7L, 11L)).thenReturn(store); when(documents.listLive(7L, 11L)).thenReturn(List.of());
        String content = "- [<b>原始</b>](../raw.md) — hook";
        var violations = MemoryIndexPolicy.validateIndex(content, path -> false);
        assertEquals("UNSAFE_TARGET", violations.get(0).code());
        var error = assertThrows(IllegalArgumentException.class, () -> new MemoryDocumentService(stores, documents, changes)
                .mutate(7, 11, MemoryMutationRequest.create("MEMORY.md", content, "key"),
                        new MemoryDocumentService.Actor("USER", "9", 9L, null, null)));
        assertEquals("invalid memory index: " + violations, error.getMessage()); assertNull(error.getCause());
        var keyed = assertInstanceOf(com.aliyun.autowonder.common.error.KeyedIllegalArgumentException.class, error);
        assertArrayEquals(new Object[]{violations.toString()}, keyed.getMessageArgs());
        assertDisplay(error, "记忆索引无效：" + violations, "Invalid memory index: " + violations);
        verify(documents, never()).insert(any()); verify(changes, never()).insert(any());
    }

    @Test void topicSerializationCarrierRetainsTheOriginalCauseAndStopsBeforeMutation() throws Exception {
        var stores = mock(MemoryStoreDao.class); var documents = mock(MemoryDocumentDao.class);
        var changes = mock(MemoryChangeDao.class); var acls = mock(MemoryStoreAclDao.class);
        var mutations = mock(MemoryDocumentService.class);
        var store = new MemoryStoreDO(); store.setId(11L); store.setTenantId(7L); store.setScope("AGENT"); store.setStatus("ACTIVE");
        when(stores.lockById(7L, 11L)).thenReturn(store); when(acls.listByStore(7L, 11L)).thenReturn(List.of());
        var topics = new MemoryTopicService(stores, documents, changes, acls, mutations,
                new MemoryStoreAccessService(), mock(AgentDao.class), mock(SquadDao.class));
        var json = mock(ObjectMapper.class);
        var cause = new com.fasterxml.jackson.core.JsonProcessingException("<b>raw external serialization diagnostic</b>") {};
        doThrow(cause).when(json).writeValueAsString(any());
        org.springframework.test.util.ReflectionTestUtils.setField(topics, "json", json);
        var error = assertThrows(IllegalArgumentException.class,
                () -> topics.deleteForUser(7, 9, true, 11, "a.md", 1, "key"));
        assertEquals("invalid topic request", error.getMessage()); assertSame(cause, error.getCause());
        assertDisplay(error, "记忆主题请求无效", "Invalid topic request");
        verifyNoInteractions(mutations); verify(changes, never()).insert(any()); verify(documents, never()).insert(any());
    }

}
