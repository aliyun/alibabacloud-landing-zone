package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.branding.PlatformBrandingDao;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.mcp.WorkitemCliUploadTokenService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkitemCliUploadControllerTest {

    private static final long USER_ID = 7L;
    private static final long WORKITEM_ID = 50063L;
    private static final long TENANT_ID = 100L;
    private static final String SECRET = "test-secret-key-that-is-long-enough-32bytes!";
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};

    WorkitemDao workitemDao;
    WorkspaceMemberDao workspaceMemberDao;
    ArtifactDao artifactDao;
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
        storage = new InMemoryObjectStorage();
        jwtService = jwtService();
        OssProperties ossProperties = new OssProperties();
        ossProperties.setArtifactBucket("artifact-bucket");
        PlatformBrandingService branding = new PlatformBrandingService(
                mock(PlatformBrandingDao.class), new InMemoryObjectStorage(), new OssProperties(),
                "https://daily.auto-wonder.example.com", "0.2.130", "x.x.x");
        tokenService = new WorkitemCliUploadTokenService(
                jwtService, workitemDao, workspaceMemberDao, branding);
        RequirementDocumentService documentService = new RequirementDocumentService(
                artifactDao, workitemDao, storage, mock(AuditLogService.class), ossProperties);

        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem(WORKITEM_ID, TENANT_ID));
        when(workspaceMemberDao.findByWorkspaceAndUser(TENANT_ID, USER_ID))
                .thenReturn(member(TENANT_ID, "READ_WRITE", 0, 0));
        when(artifactDao.listByWorkitemAndType(TENANT_ID, WORKITEM_ID, RequirementDocumentService.TYPE))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            ArtifactDO artifact = invocation.getArgument(0);
            artifact.setId(77L);
            return null;
        }).when(artifactDao).insert(any(ArtifactDO.class));

        mvc = MockMvcBuilders.standaloneSetup(new WorkitemCliUploadController(
                tokenService, workitemDao, documentService)).build();
        uploadPath = "/api/cli/workitems/" + WORKITEM_ID + "/requirement-documents";
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
                storage.get("artifact-bucket/t/100/workitem/50063/requirements/requirements.md"),
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
    void missingWorkitemReturns404() throws Exception {
        String token = mintToken();
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(null);

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
    void oneTokenUploadsToTwoWorkitemsInTwoWorkspaces() throws Exception {
        long otherWorkitemId = 50064L;
        long otherTenantId = 200L;
        when(workitemDao.findById(otherWorkitemId)).thenReturn(workitem(otherWorkitemId, otherTenantId));
        when(workspaceMemberDao.findByWorkspaceAndUser(otherTenantId, USER_ID))
                .thenReturn(member(otherTenantId, "READ_WRITE", 0, 0));
        when(artifactDao.listByWorkitemAndType(otherTenantId, otherWorkitemId,
                RequirementDocumentService.TYPE)).thenReturn(List.of());
        String token = mintToken();

        mvc.perform(multipart(uploadPath)
                        .file(markdown("first.md", "# First"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(multipart("/api/cli/workitems/" + otherWorkitemId + "/requirement-documents")
                        .file(markdown("second.md", "# Second"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertEquals("# Second", new String(
                storage.get("artifact-bucket/t/200/workitem/50064/requirements/second.md"),
                StandardCharsets.UTF_8));
    }

    @Test
    void unsupportedExtensionAndBadMagicBytesReturn400() throws Exception {
        String token = mintToken();

        mvc.perform(multipart(uploadPath)
                        .file(new MockMultipartFile("files", "a.txt", "text/plain",
                                "plain".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        mvc.perform(multipart(uploadPath)
                        .file(new MockMultipartFile("files", "fake.png", "image/png",
                                "not-a-png".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
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
        when(artifactDao.listByWorkitemAndType(TENANT_ID, WORKITEM_ID, RequirementDocumentService.TYPE))
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
                USER_ID, WORKITEM_ID).getToken();
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
