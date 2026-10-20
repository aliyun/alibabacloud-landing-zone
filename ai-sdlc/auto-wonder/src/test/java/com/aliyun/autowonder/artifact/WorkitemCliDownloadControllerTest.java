package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.branding.PlatformBrandingDao;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.mcp.WorkitemCliDownloadTokenService;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.storage.StoredObject;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkitemCliDownloadControllerTest {

    private static final long USER_ID = 7L;
    private static final long WORKITEM_ID = 50063L;
    private static final long TENANT_ID = 100L;
    private static final String SECRET = "test-secret-key-that-is-long-enough-32bytes!";
    private static final String BUCKET = "artifact-bucket";
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};

    WorkitemDao workitemDao;
    WorkspaceMemberDao workspaceMemberDao;
    ArtifactDao artifactDao;
    InMemoryObjectStorage storage;
    WorkitemCliDownloadTokenService tokenService;
    JwtService jwtService;
    MockMvc mvc;
    String indexPath;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        workspaceMemberDao = mock(WorkspaceMemberDao.class);
        artifactDao = mock(ArtifactDao.class);
        storage = new InMemoryObjectStorage();
        jwtService = jwtService();
        OssProperties ossProperties = new OssProperties();
        ossProperties.setArtifactBucket(BUCKET);
        PlatformBrandingService branding = new PlatformBrandingService(
                mock(PlatformBrandingDao.class), new InMemoryObjectStorage(), new OssProperties(),
                "https://daily.auto-wonder.example.com", "0.2.130", "x.x.x", false);
        tokenService = new WorkitemCliDownloadTokenService(
                jwtService, workitemDao, workspaceMemberDao, branding);
        RequirementDocumentService documentService = new RequirementDocumentService(
                artifactDao, workitemDao, storage, mock(AuditLogService.class), ossProperties);

        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem(WORKITEM_ID, TENANT_ID));
        when(workspaceMemberDao.findByWorkspaceAndUser(TENANT_ID, USER_ID))
                .thenReturn(member(TENANT_ID, "READ_ONLY", 0, 0));

        mvc = MockMvcBuilders.standaloneSetup(new WorkitemCliDownloadController(
                tokenService, workitemDao, documentService)).build();
        indexPath = "/api/cli/workitems/" + WORKITEM_ID + "/requirement-documents/index";
    }

    @Test
    void validTokenListsDocumentMetadataInIndex() throws Exception {
        ArtifactDO md = seedDocument(TENANT_ID, WORKITEM_ID, 77L, "requirements.md",
                "# Requirements".getBytes(StandardCharsets.UTF_8));
        when(artifactDao.listByWorkitemAndType(TENANT_ID, WORKITEM_ID, RequirementDocumentService.TYPE))
                .thenReturn(List.of(md));
        String token = mintToken();

        var result = mvc.perform(get(indexPath).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(77))
                .andExpect(jsonPath("$.data[0].name").value("requirements/requirements.md"))
                .andReturn();

        assertFalse(result.getResponse().getContentAsString().contains(token));
    }

    @Test
    void validTokenStreamsContentWithAttachmentDisposition() throws Exception {
        byte[] bytes = "# Requirements".getBytes(StandardCharsets.UTF_8);
        seedDocument(TENANT_ID, WORKITEM_ID, 77L, "requirements.md", bytes);
        when(artifactDao.findWorkitemByTenantAndId(TENANT_ID, 77L))
                .thenReturn(storedArtifact(TENANT_ID, WORKITEM_ID, 77L, "requirements.md", bytes));
        String token = mintToken();

        var result = mvc.perform(get(contentPath(WORKITEM_ID, 77L))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/markdown"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("requirements.md")))
                .andReturn();

        assertArrayEquals(bytes, result.getResponse().getContentAsByteArray());
        assertFalse(result.getResponse().getContentAsString().contains(token));
    }

    @Test
    void binaryContentIsStreamedIntact() throws Exception {
        seedDocument(TENANT_ID, WORKITEM_ID, 78L, "diagram.png", PNG);
        when(artifactDao.findWorkitemByTenantAndId(TENANT_ID, 78L))
                .thenReturn(storedArtifact(TENANT_ID, WORKITEM_ID, 78L, "diagram.png", PNG));
        String token = mintToken();

        var result = mvc.perform(get(contentPath(WORKITEM_ID, 78L))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn();

        assertArrayEquals(PNG, result.getResponse().getContentAsByteArray());
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        when(artifactDao.listByWorkitemAndType(TENANT_ID, WORKITEM_ID, RequirementDocumentService.TYPE))
                .thenReturn(List.of());
        String token = mintToken();
        for (String scheme : new String[]{"bearer ", "BEARER ", "BeArEr "}) {
            mvc.perform(get(indexPath).header("Authorization", scheme + token))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void missingOrMalformedCredentialsReturn401() throws Exception {
        String expired = WorkitemCliDownloadTokenService.TOKEN_PREFIX
                + jwtService.signUserPurpose(USER_ID, WorkitemCliDownloadTokenService.PURPOSE, -1);
        String wrongPurpose = WorkitemCliDownloadTokenService.TOKEN_PREFIX
                + jwtService.signUserPurpose(USER_ID, "dispatch-mcp", 1800);
        String valid = mintToken();
        String tampered = valid.substring(0, valid.length() - 4) + "AAAA";

        for (String authorization : new String[]{null, "Bearer", "Bearer ",
                "Bearer awupload_x", "Bearer " + expired, "Bearer " + wrongPurpose,
                "Bearer " + tampered}) {
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder indexReq = get(indexPath);
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder contentReq =
                    get(contentPath(WORKITEM_ID, 77L));
            if (authorization != null) {
                indexReq = indexReq.header("Authorization", authorization);
                contentReq = contentReq.header("Authorization", authorization);
            }
            mvc.perform(indexReq).andExpect(status().isUnauthorized());
            mvc.perform(contentReq).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void missingWorkitemReturns404() throws Exception {
        String token = mintToken();
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(null);

        mvc.perform(get(indexPath).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mvc.perform(get(contentPath(WORKITEM_ID, 77L)).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void readOnlyMembershipCanDownload() throws Exception {
        when(workspaceMemberDao.findByWorkspaceAndUser(TENANT_ID, USER_ID))
                .thenReturn(member(TENANT_ID, "READ_ONLY", 0, 0));
        when(artifactDao.listByWorkitemAndType(TENANT_ID, WORKITEM_ID, RequirementDocumentService.TYPE))
                .thenReturn(List.of());
        String token = mintToken();

        mvc.perform(get(indexPath).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void inactiveOrMissingMembershipReturns403() throws Exception {
        String token = mintToken();
        for (WorkspaceMemberDO member : new WorkspaceMemberDO[]{
                null,
                member(TENANT_ID, "READ_ONLY", 1, 0),
                member(TENANT_ID, "READ_WRITE", 0, 1)}) {
            when(workspaceMemberDao.findByWorkspaceAndUser(TENANT_ID, USER_ID)).thenReturn(member);

            mvc.perform(get(indexPath).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mvc.perform(get(contentPath(WORKITEM_ID, 77L)).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void unknownArtifactReturns404() throws Exception {
        when(artifactDao.findWorkitemByTenantAndId(TENANT_ID, 999L)).thenReturn(null);
        String token = mintToken();

        mvc.perform(get(contentPath(WORKITEM_ID, 999L)).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void artifactOwnedByAnotherWorkitemReturns404() throws Exception {
        when(artifactDao.findWorkitemByTenantAndId(TENANT_ID, 77L))
                .thenReturn(storedArtifact(TENANT_ID, 50999L, 77L, "requirements.md",
                        "# Other".getBytes(StandardCharsets.UTF_8)));
        String token = mintToken();

        mvc.perform(get(contentPath(WORKITEM_ID, 77L)).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingStorageBytesReturns404() throws Exception {
        ArtifactDO orphan = storedArtifact(TENANT_ID, WORKITEM_ID, 77L, "requirements.md",
                "# Requirements".getBytes(StandardCharsets.UTF_8));
        orphan.setOssRef(BUCKET + "/t/100/workitem/50063/requirements/absent.md");
        when(artifactDao.findWorkitemByTenantAndId(TENANT_ID, 77L)).thenReturn(orphan);
        String token = mintToken();

        mvc.perform(get(contentPath(WORKITEM_ID, 77L)).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void oneTokenDownloadsFromTwoWorkitemsInTwoWorkspaces() throws Exception {
        long otherWorkitemId = 50064L;
        long otherTenantId = 200L;
        when(workitemDao.findById(otherWorkitemId)).thenReturn(workitem(otherWorkitemId, otherTenantId));
        when(workspaceMemberDao.findByWorkspaceAndUser(otherTenantId, USER_ID))
                .thenReturn(member(otherTenantId, "READ_ONLY", 0, 0));
        byte[] firstBytes = "# First".getBytes(StandardCharsets.UTF_8);
        byte[] secondBytes = "# Second".getBytes(StandardCharsets.UTF_8);
        seedDocument(TENANT_ID, WORKITEM_ID, 77L, "first.md", firstBytes);
        seedDocument(otherTenantId, otherWorkitemId, 88L, "second.md", secondBytes);
        when(artifactDao.findWorkitemByTenantAndId(TENANT_ID, 77L))
                .thenReturn(storedArtifact(TENANT_ID, WORKITEM_ID, 77L, "first.md", firstBytes));
        when(artifactDao.findWorkitemByTenantAndId(otherTenantId, 88L))
                .thenReturn(storedArtifact(otherTenantId, otherWorkitemId, 88L, "second.md", secondBytes));
        String token = mintToken();

        mvc.perform(get(contentPath(WORKITEM_ID, 77L)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        var other = mvc.perform(get(contentPath(otherWorkitemId, 88L))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        assertArrayEquals(secondBytes, other.getResponse().getContentAsByteArray());
    }

    // ---- fixtures ----

    private String contentPath(long workitemId, long artifactId) {
        return "/api/cli/workitems/" + workitemId + "/requirement-documents/" + artifactId + "/content";
    }

    private String mintToken() {
        return tokenService.mint(
                com.aliyun.autowonder.mcp.McpAccessTokenService.CredentialType.LONG_LIVED,
                USER_ID, WORKITEM_ID).getToken();
    }

    @Test
    void issuedTokenStillDownloadsWhenArtifactDomainBecomesReadOnly() throws Exception {
        String token = mintToken();
        // Read-only role permissions continue to allow document downloads.
        WorkspaceMemberDO forbidden = member(TENANT_ID, "READ_ONLY", 0, 0);
        forbidden.setReadOnlyDomains("[\"ARTIFACT\"]");
        when(workspaceMemberDao.findByWorkspaceAndUser(TENANT_ID, USER_ID)).thenReturn(forbidden);

        mvc.perform(get(indexPath).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private String key(long tenantId, long workitemId, String filename) {
        return "t/" + tenantId + "/workitem/" + workitemId + "/requirements/" + filename;
    }

    // Puts bytes into storage and returns an ArtifactDO whose ossRef resolves back to them.
    private ArtifactDO seedDocument(long tenantId, long workitemId, long artifactId,
                                    String filename, byte[] bytes) {
        StoredObject stored = storage.put(BUCKET, key(tenantId, workitemId, filename), bytes);
        ArtifactDO artifact = new ArtifactDO();
        artifact.setId(artifactId);
        artifact.setTenantId(tenantId);
        artifact.setSourceType("WORKITEM");
        artifact.setWorkitemId(workitemId);
        artifact.setName(RequirementDocumentService.PREFIX + filename);
        artifact.setType(RequirementDocumentService.TYPE);
        artifact.setOssRef(stored.getOssRef());
        artifact.setSize(stored.getSize());
        return artifact;
    }

    // An ArtifactDO for content lookups whose bytes are already present in storage.
    private ArtifactDO storedArtifact(long tenantId, long workitemId, long artifactId,
                                      String filename, byte[] bytes) {
        return seedDocument(tenantId, workitemId, artifactId, filename, bytes);
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
