package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.branding.PlatformBrandingDao;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.mcp.WorkitemCliUploadTokenService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskDO;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskDao;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScheduledTaskCliUploadControllerTest {

    private static final long USER_ID = 7L;
    private static final long TASK_ID = 321L;
    private static final long TENANT_ID = 100L;
    // The awupload_ token is user-level; minting reuses the workitem preflight.
    private static final long MINT_WORKITEM_ID = 50063L;
    private static final String SECRET = "test-secret-key-that-is-long-enough-32bytes!";
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    private static final String SOURCE = ExecutionSourceType.SCHEDULED_TASK.name();

    WorkitemDao workitemDao;
    WorkspaceMemberDao workspaceMemberDao;
    ArtifactDao artifactDao;
    ScheduledTaskDao scheduledTaskDao;
    InMemoryObjectStorage storage;
    WorkitemCliUploadTokenService tokenService;
    JwtService jwtService;
    MockMvc mvc;
    String uploadPath;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        workspaceMemberDao = mock(WorkspaceMemberDao.class);
        artifactDao = mock(ArtifactDao.class);
        scheduledTaskDao = mock(ScheduledTaskDao.class);
        storage = new InMemoryObjectStorage();
        jwtService = jwtService();
        OssProperties ossProperties = new OssProperties();
        ossProperties.setArtifactBucket("artifact-bucket");
        PlatformBrandingService branding = new PlatformBrandingService(
                mock(PlatformBrandingDao.class), new InMemoryObjectStorage(), new OssProperties(),
                "https://daily.auto-wonder.example.com", "0.2.130", "x.x.x", false);
        tokenService = new WorkitemCliUploadTokenService(
                jwtService, workitemDao, workspaceMemberDao, branding);
        RequirementDocumentService documentService = new RequirementDocumentService(
                artifactDao, workitemDao, scheduledTaskDao, storage,
                mock(AuditLogService.class), ossProperties);

        when(workitemDao.findById(MINT_WORKITEM_ID)).thenReturn(workitem(MINT_WORKITEM_ID, TENANT_ID));
        when(workspaceMemberDao.findByWorkspaceAndUser(TENANT_ID, USER_ID))
                .thenReturn(member(TENANT_ID, "READ_WRITE", 0, 0));
        when(scheduledTaskDao.findAnyById(TASK_ID)).thenReturn(task(TASK_ID, TENANT_ID, "ACTIVE"));
        when(scheduledTaskDao.findByIdForUpdate(TENANT_ID, TASK_ID))
                .thenReturn(task(TASK_ID, TENANT_ID, "ACTIVE"));
        when(artifactDao.listBySource(TENANT_ID, SOURCE, TASK_ID, RequirementDocumentService.TYPE))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            ArtifactDO artifact = invocation.getArgument(0);
            artifact.setId(88L);
            return null;
        }).when(artifactDao).insert(any(ArtifactDO.class));

        mvc = MockMvcBuilders.standaloneSetup(new ScheduledTaskCliUploadController(
                tokenService, scheduledTaskDao, documentService)).build();
        uploadPath = "/api/cli/scheduled-tasks/" + TASK_ID + "/documents";
    }

    @Test
    void validTokenUploadsRepeatedFilesInOrder() throws Exception {
        String token = mintToken();

        var result = mvc.perform(multipart(uploadPath)
                        .file(markdown("requirements.md", "# Requirements"))
                        .file(markdown("design.md", "# Design"))
                        .file(new MockMultipartFile("files", "architecture.png", "image/png", PNG))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].name").value("requirements/requirements.md"))
                .andExpect(jsonPath("$.data[1].name").value("requirements/design.md"))
                .andExpect(jsonPath("$.data[2].name").value("requirements/architecture.png"))
                .andReturn();

        assertEquals("# Requirements", new String(
                storage.get("artifact-bucket/t/100/scheduled-task/321/requirements/requirements.md"),
                StandardCharsets.UTF_8));
        assertFalse(result.getResponse().getContentAsString().contains(token));
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        String token = mintToken();
        for (String scheme : new String[]{"bearer ", "BEARER ", "BeArEr "}) {
            mvc.perform(multipart(uploadPath)
                            .file(markdown("scheme.md", "# Scheme"))
                            .header("Authorization", scheme + token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void missingOrMalformedCredentialsReturn401() throws Exception {
        String expired = WorkitemCliUploadTokenService.TOKEN_PREFIX
                + jwtService.signUserPurpose(USER_ID, WorkitemCliUploadTokenService.PURPOSE, -1);
        String wrongPurpose = WorkitemCliUploadTokenService.TOKEN_PREFIX
                + jwtService.signUserPurpose(USER_ID, "dispatch-mcp", 1800);
        String valid = mintToken();
        String tampered = valid.substring(0, valid.length() - 4) + "AAAA";

        for (String authorization : new String[]{null, "Bearer", "Bearer ",
                "Bearer awdispatch_x", "Bearer " + expired, "Bearer " + wrongPurpose,
                "Bearer " + tampered}) {
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                    multipart(uploadPath).file(markdown("a.md", "# A"));
            if (authorization != null) {
                request = request.header("Authorization", authorization);
            }
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void missingScheduledTaskReturns404() throws Exception {
        String token = mintToken();
        when(scheduledTaskDao.findAnyById(TASK_ID)).thenReturn(null);

        mvc.perform(multipart(uploadPath)
                        .file(markdown("a.md", "# A"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void readOnlyOrInactiveMembershipReturns403() throws Exception {
        String token = mintToken();
        for (WorkspaceMemberDO member : new WorkspaceMemberDO[]{
                null,
                member(TENANT_ID, "READ_ONLY", 0, 0),
                member(TENANT_ID, "READ_WRITE", 1, 0),
                member(TENANT_ID, "READ_WRITE", 0, 1)}) {
            when(workspaceMemberDao.findByWorkspaceAndUser(TENANT_ID, USER_ID)).thenReturn(member);

            mvc.perform(multipart(uploadPath)
                            .file(markdown("a.md", "# A"))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void archivedTaskReturns409() throws Exception {
        String token = mintToken();
        when(scheduledTaskDao.findAnyById(TASK_ID)).thenReturn(task(TASK_ID, TENANT_ID, "ARCHIVED"));
        when(scheduledTaskDao.findByIdForUpdate(TENANT_ID, TASK_ID))
                .thenReturn(task(TASK_ID, TENANT_ID, "ARCHIVED"));

        mvc.perform(multipart(uploadPath)
                        .file(markdown("a.md", "# A"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());
    }

    @Test
    void oneTokenUploadsToTwoTasksInTwoWorkspaces() throws Exception {
        long otherTaskId = 322L;
        long otherTenantId = 200L;
        when(scheduledTaskDao.findAnyById(otherTaskId)).thenReturn(task(otherTaskId, otherTenantId, "ACTIVE"));
        when(scheduledTaskDao.findByIdForUpdate(otherTenantId, otherTaskId))
                .thenReturn(task(otherTaskId, otherTenantId, "ACTIVE"));
        when(workspaceMemberDao.findByWorkspaceAndUser(otherTenantId, USER_ID))
                .thenReturn(member(otherTenantId, "READ_WRITE", 0, 0));
        when(artifactDao.listBySource(otherTenantId, SOURCE, otherTaskId,
                RequirementDocumentService.TYPE)).thenReturn(List.of());
        String token = mintToken();

        mvc.perform(multipart(uploadPath)
                        .file(markdown("first.md", "# First"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(multipart("/api/cli/scheduled-tasks/" + otherTaskId + "/documents")
                        .file(markdown("second.md", "# Second"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertEquals("# Second", new String(
                storage.get("artifact-bucket/t/200/scheduled-task/322/requirements/second.md"),
                StandardCharsets.UTF_8));
    }

    @Test
    void unsupportedExtensionAndBadMagicBytesReturn400() throws Exception {
        String token = mintToken();

        for (String filename : new String[]{"a.exe", "fake.png", "fake.jpg", "fake.jpeg",
                "fake.webp", "fake.pdf"}) {
            mvc.perform(multipart(uploadPath)
                            .file(new MockMultipartFile("files", filename, "application/octet-stream",
                                    "not-a-real-file".getBytes(StandardCharsets.UTF_8)))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void invalidUtf8TextReturns400() throws Exception {
        String token = mintToken();

        mvc.perform(multipart(uploadPath)
                        .file(new MockMultipartFile("files", "broken.txt", "text/plain",
                                new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x01}))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void allSupportedFormatsUploadSuccessfully() throws Exception {
        String token = mintToken();
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
                'J', 'F', 'I', 'F', 0x00};
        byte[] webp = {'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P',
                'V', 'P', '8', ' '};
        byte[] pdf = "%PDF-1.4 minimal spec".getBytes(StandardCharsets.UTF_8);

        mvc.perform(multipart(uploadPath)
                        .file(new MockMultipartFile("files", "notes.txt", "text/plain",
                                "plain notes".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "prd.html", "text/html",
                                "<html><body>PRD</body></html>".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "spec.pdf", "application/pdf", pdf))
                        .file(new MockMultipartFile("files", "photo.jpg", "image/jpeg", jpeg))
                        .file(new MockMultipartFile("files", "icon.webp", "image/webp", webp))
                        .file(new MockMultipartFile("files", "legacy.markdown", "text/markdown",
                                "# Legacy".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[0].name").value("requirements/notes.txt"))
                .andExpect(jsonPath("$.data[1].name").value("requirements/prd.html"))
                .andExpect(jsonPath("$.data[2].name").value("requirements/spec.pdf"))
                .andExpect(jsonPath("$.data[3].name").value("requirements/photo.jpg"))
                .andExpect(jsonPath("$.data[4].name").value("requirements/icon.webp"))
                .andExpect(jsonPath("$.data[5].name").value("requirements/legacy.markdown"));

        assertEquals("plain notes", new String(
                storage.get("artifact-bucket/t/100/scheduled-task/321/requirements/notes.txt"),
                StandardCharsets.UTF_8));
        assertArrayEquals(pdf, storage.get(
                "artifact-bucket/t/100/scheduled-task/321/requirements/spec.pdf"));
    }

    @Test
    void oversizeFileReturns400() throws Exception {
        byte[] oversize = new byte[(int) (5L * 1024 * 1024) + 1];
        java.util.Arrays.fill(oversize, (byte) 'a');

        mvc.perform(multipart(uploadPath)
                        .file(new MockMultipartFile("files", "big.md", "text/markdown", oversize))
                        .header("Authorization", "Bearer " + mintToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateFilenameReturns409() throws Exception {
        ArtifactDO existing = new ArtifactDO();
        existing.setName("requirements/a.md");
        existing.setSize(10L);
        when(artifactDao.listBySource(TENANT_ID, SOURCE, TASK_ID, RequirementDocumentService.TYPE))
                .thenReturn(List.of(existing));

        mvc.perform(multipart(uploadPath)
                        .file(markdown("a.md", "# A"))
                        .header("Authorization", "Bearer " + mintToken()))
                .andExpect(status().isConflict());
    }

    // ---- fixtures ----

    private String mintToken() {
        return tokenService.mint(
                com.aliyun.autowonder.mcp.McpAccessTokenService.CredentialType.LONG_LIVED,
                USER_ID, MINT_WORKITEM_ID).getToken();
    }

    private static MockMultipartFile markdown(String filename, String content) {
        return new MockMultipartFile("files", filename, "text/markdown",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private JwtService jwtService() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret(SECRET);
        props.setAccessTtlSeconds(3600);
        props.setRefreshTtlSeconds(7200);
        return new JwtService(props);
    }

    private static WorkitemDO workitem(long id, long tenantId) {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(id);
        workitem.setTenantId(tenantId);
        return workitem;
    }

    private static ScheduledTaskDO task(long id, long workspaceId, String status) {
        ScheduledTaskDO task = new ScheduledTaskDO();
        task.setId(id);
        task.setWorkspaceId(workspaceId);
        task.setStatus(status);
        return task;
    }

    private static WorkspaceMemberDO member(long tenantId, String accessLevel,
                                            int status, int isDeleted) {
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setTenantId(tenantId);
        member.setUserId(USER_ID);
        member.setAccessLevel(accessLevel);
        member.setStatus(status);
        member.setIsDeleted(isDeleted);
        return member;
    }
}
