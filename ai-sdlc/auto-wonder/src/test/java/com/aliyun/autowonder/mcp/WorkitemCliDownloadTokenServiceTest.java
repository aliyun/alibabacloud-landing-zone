package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.branding.PlatformBrandingDao;
import com.aliyun.autowonder.branding.PlatformBrandingDO;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.mcp.dto.WorkitemCliDownloadTokenVO;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkitemCliDownloadTokenServiceTest {

    private static final long USER_ID = 7L;
    private static final long TENANT_ID = 100L;
    private static final long WORKITEM_ID = 50063L;
    private static final String SECRET = "test-secret-key-that-is-long-enough-32bytes!";

    @Test
    void personalLongLivedCredentialMintsScopedToken() {
        WorkitemCliDownloadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");

        WorkitemCliDownloadTokenVO vo = service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID);

        assertTrue(vo.getToken().startsWith(WorkitemCliDownloadTokenService.TOKEN_PREFIX));
        assertEquals("Bearer", vo.getTokenType());
        assertEquals(1800, vo.getExpiresInSeconds());
        long expiresAt = Instant.parse(vo.getExpiresAt()).getEpochSecond();
        long expected = Instant.now().getEpochSecond() + 1800;
        assertTrue(Math.abs(expiresAt - expected) <= 5);
        assertEquals("https://daily.auto-wonder.example.com", vo.getServerUrl());
        assertEquals("0.2.130", vo.getRuntimeVersion());
        assertEquals("AUTOWONDER_DOWNLOAD_TOKEN", vo.getTokenEnvName());
        assertEquals(com.aliyun.autowonder.artifact.RequirementDocumentService.SUPPORTED_EXTENSIONS,
                vo.getSupportedExtensions());
        assertTrue(vo.getSupportedExtensions().containsAll(java.util.List.of(
                ".docx", ".doc", ".java", ".py", ".zip")));
        assertFalse(vo.getToken().contains(SECRET));
    }

    @Test
    void commandsUseConfiguredDeploymentValuesAndDownloadShape() {
        WorkitemCliDownloadTokenService service = service("http://autowonder.internal.example.com:8080", "0.9.9-rc.1");

        WorkitemCliDownloadTokenVO vo = service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID);

        for (String command : new String[]{vo.getCommand(), vo.getPowershellCommand()}) {
            assertTrue(command.contains("autowonder@0.9.9-rc.1"), command);
            assertTrue(command.contains("http://autowonder.internal.example.com:8080"), command);
            assertTrue(command.contains("--workitem-id " + WORKITEM_ID), command);
            assertTrue(command.contains("workitem download"), command);
            assertTrue(command.contains("--file <name-or-id>"), command);
            assertTrue(command.contains("--output-dir <dir>"), command);
            assertTrue(command.contains("--json"), command);
            assertFalse(command.contains("autowonder@latest"));
            assertFalse(command.contains("auto-wonder.alibaba.net"));
            assertFalse(command.contains("workitem upload"));
        }
        assertTrue(vo.getCommand().startsWith("export AUTOWONDER_DOWNLOAD_TOKEN='awdownload_"));
        assertTrue(vo.getPowershellCommand().startsWith("$env:AUTOWONDER_DOWNLOAD_TOKEN='awdownload_"));
        assertEquals(
                "npx -y autowonder@0.9.9-rc.1 workitem download"
                        + " --server-url http://autowonder.internal.example.com:8080"
                        + " --workitem-id <workitem-id>"
                        + " --file <name-or-id> --output-dir <dir> --json",
                service.commandTemplate());
    }

    @Test
    void dispatchCredentialMintsScopedTokenLikeLongLived() {
        WorkitemCliDownloadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");

        WorkitemCliDownloadTokenVO vo = service.mint(
                McpAccessTokenService.CredentialType.DISPATCH, USER_ID, WORKITEM_ID);

        assertTrue(vo.getToken().startsWith(WorkitemCliDownloadTokenService.TOKEN_PREFIX));
        assertEquals("Bearer", vo.getTokenType());
        assertEquals(1800, vo.getExpiresInSeconds());
        assertEquals(USER_ID, service.authenticate(vo.getToken()));
    }

    @Test
    void conversationCredentialMintsScopedTokenLikeLongLived() {
        WorkitemCliDownloadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");

        WorkitemCliDownloadTokenVO vo = service.mint(
                McpAccessTokenService.CredentialType.CONVERSATION, USER_ID, WORKITEM_ID);

        assertTrue(vo.getToken().startsWith(WorkitemCliDownloadTokenService.TOKEN_PREFIX));
        assertEquals("Bearer", vo.getTokenType());
        assertEquals(1800, vo.getExpiresInSeconds());
        assertEquals(USER_ID, service.authenticate(vo.getToken()));
    }

    @Test
    void missingWorkitemIsRejected() {
        WorkitemCliDownloadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID));
        assertEquals("13003", e.getCode());
    }

    @Test
    void workitemWithoutTenantIsRejected() {
        WorkitemCliDownloadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");
        WorkitemDO orphan = new WorkitemDO();
        orphan.setId(WORKITEM_ID);
        orphan.setTenantId(null);
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(orphan);

        BizException e = assertThrows(BizException.class, () -> service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID));
        assertEquals("13003", e.getCode());
    }

    @Test
    void workitemFromAnotherOrgCannotBeUsed() {
        WorkitemCliDownloadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");
        WorkitemDO foreign = new WorkitemDO();
        foreign.setId(WORKITEM_ID);
        foreign.setTenantId(999L);
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(foreign);
        when(workspaceMemberDao.findByWorkspaceAndUser(999L, USER_ID)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID));
        assertEquals("10403", e.getCode());
    }

    @Test
    void readOnlyMembershipCanMint() {
        WorkitemCliDownloadTokenService service = serviceWithMember(member("READ_ONLY"));

        WorkitemCliDownloadTokenVO vo = service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID);

        assertTrue(vo.getToken().startsWith(WorkitemCliDownloadTokenService.TOKEN_PREFIX));
    }

    @Test
    void readWriteAndAdminMembershipCanMint() {
        for (String accessLevel : new String[]{"READ_WRITE", "ADMIN"}) {
            WorkitemCliDownloadTokenService service = serviceWithMember(member(accessLevel));
            WorkitemCliDownloadTokenVO vo = service.mint(
                    McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID);
            assertTrue(vo.getToken().startsWith(WorkitemCliDownloadTokenService.TOKEN_PREFIX));
        }
    }

    @Test
    void inactiveOrMissingMembershipCannotMint() {
        for (WorkspaceMemberDO member : new WorkspaceMemberDO[]{
                null, member("READ_ONLY", 1, 0), member("READ_ONLY", 0, 1)}) {
            WorkitemCliDownloadTokenService service = serviceWithMember(member);
            BizException e = assertThrows(BizException.class, () -> service.mint(
                    McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID));
            assertEquals("10403", e.getCode());
        }
    }

    @Test
    void unknownAccessLevelCannotMint() {
        WorkitemCliDownloadTokenService service = serviceWithMember(member("NOT_A_LEVEL"));

        BizException e = assertThrows(BizException.class, () -> service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID));
        assertEquals("10403", e.getCode());
    }

    @Test
    void mintedTokenRoundTripsThroughAuthenticate() {
        WorkitemCliDownloadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");
        WorkitemCliDownloadTokenVO vo = service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID);

        assertEquals(USER_ID, service.authenticate(vo.getToken()));
    }

    @Test
    void authenticateRejectsInvalidTokens() {
        WorkitemCliDownloadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");
        JwtService jwt = jwtService();
        String wrongPurpose = WorkitemCliDownloadTokenService.TOKEN_PREFIX
                + jwt.signUserPurpose(USER_ID, "dispatch-mcp", 1800);
        String expired = WorkitemCliDownloadTokenService.TOKEN_PREFIX
                + jwt.signUserPurpose(USER_ID, WorkitemCliDownloadTokenService.PURPOSE, -1);
        String valid = WorkitemCliDownloadTokenService.TOKEN_PREFIX
                + jwt.signUserPurpose(USER_ID, WorkitemCliDownloadTokenService.PURPOSE, 1800);
        String tampered = valid.substring(0, valid.length() - 4) + "AAAA";

        for (String token : new String[]{null, "", "awupload_x", wrongPurpose, expired, tampered}) {
            BizException e = assertThrows(BizException.class, () -> service.authenticate(token));
            assertEquals("10401", e.getCode());
        }
    }

    @Test
    void commandTemplateQuotesServerUrlWithTrailingSlashNormalized() {
        WorkitemCliDownloadTokenService service = service("https://autowonder.example.com/", "0.2.130");

        assertTrue(service.commandTemplate().contains("--server-url https://autowonder.example.com "));
    }

    @Test
    void tokenEnvHintPointsAtDownloadTokenTool() {
        WorkitemCliDownloadTokenService service = service("https://autowonder.example.com/", "0.2.130");

        String hint = service.tokenEnvHint();
        assertTrue(hint.startsWith("export AUTOWONDER_DOWNLOAD_TOKEN="));
        assertTrue(hint.contains("autowonder.workitem_cli_download_token"));
    }

    @Test
    void serverUrlAndCommandsFollowTheConfiguredBrandingDomainWithoutRestart() {
        PlatformBrandingDao brandingDao = mock(PlatformBrandingDao.class);
        when(brandingDao.findActive()).thenReturn(brandingRow("https://wonder.example.com"));
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem());
        when(workspaceMemberDao.findByWorkspaceAndUser(TENANT_ID, USER_ID)).thenReturn(member("READ_ONLY"));
        PlatformBrandingService branding = new PlatformBrandingService(
                brandingDao, new InMemoryObjectStorage(), new OssProperties(),
                "https://daily.auto-wonder.example.com", "0.2.130", "x.x.x", false);
        WorkitemCliDownloadTokenService service =
                new WorkitemCliDownloadTokenService(jwtService(), workitemDao, workspaceMemberDao, branding);

        WorkitemCliDownloadTokenVO vo = service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID);

        assertEquals("https://wonder.example.com", vo.getServerUrl());
        assertTrue(vo.getCommand().contains("--server-url 'https://wonder.example.com'"), vo.getCommand());
        assertTrue(vo.getPowershellCommand().contains("--server-url 'https://wonder.example.com'"));
        assertTrue(service.commandTemplate().contains("--server-url https://wonder.example.com "));

        when(brandingDao.findActive()).thenReturn(brandingRow(null));
        WorkitemCliDownloadTokenVO cleared = service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID);

        assertEquals("https://daily.auto-wonder.example.com", cleared.getServerUrl());
    }

    // ---- fixtures ----

    private final WorkitemDao workitemDao = mock(WorkitemDao.class);
    private final WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);

    private JwtService jwtService() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret(SECRET);
        props.setAccessTtlSeconds(3600);
        props.setRefreshTtlSeconds(7200);
        return new JwtService(props);
    }

    private WorkitemCliDownloadTokenService service(String baseUrl, String runtimeVersion) {
        return serviceWithMember(baseUrl, runtimeVersion, member("READ_ONLY"));
    }

    private WorkitemCliDownloadTokenService serviceWithMember(WorkspaceMemberDO member) {
        return serviceWithMember("https://daily.auto-wonder.example.com", "0.2.130", member);
    }

    private WorkitemCliDownloadTokenService serviceWithMember(
            String baseUrl, String runtimeVersion, WorkspaceMemberDO member) {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem());
        when(workspaceMemberDao.findByWorkspaceAndUser(TENANT_ID, USER_ID)).thenReturn(member);
        PlatformBrandingDao brandingDao = mock(PlatformBrandingDao.class);
        PlatformBrandingService branding = new PlatformBrandingService(
                brandingDao, new InMemoryObjectStorage(), new OssProperties(),
                baseUrl, runtimeVersion, "x.x.x", false);
        return new WorkitemCliDownloadTokenService(jwtService(), workitemDao, workspaceMemberDao, branding);
    }

    private static WorkitemDO workitem() {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(WORKITEM_ID);
        workitem.setTenantId(TENANT_ID);
        return workitem;
    }

    private static PlatformBrandingDO brandingRow(String domain) {
        PlatformBrandingDO row = new PlatformBrandingDO();
        row.setPlatformName("WonderHub");
        row.setThemeKey("ocean-blue");
        row.setPrimaryColor("#2563eb");
        row.setDomain(domain);
        return row;
    }

    private static WorkspaceMemberDO member(String accessLevel) {
        return member(accessLevel, 0, 0);
    }

    private static WorkspaceMemberDO member(String accessLevel, int status, int isDeleted) {
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setTenantId(TENANT_ID);
        member.setUserId(USER_ID);
        member.setAccessLevel(accessLevel);
        member.setStatus(status);
        member.setIsDeleted(isDeleted);
        return member;
    }
}
