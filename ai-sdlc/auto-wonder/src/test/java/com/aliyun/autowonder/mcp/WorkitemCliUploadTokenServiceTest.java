package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.branding.PlatformBrandingDao;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.mcp.dto.WorkitemCliUploadTokenVO;
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

class WorkitemCliUploadTokenServiceTest {

    private static final long USER_ID = 7L;
    private static final long TENANT_ID = 100L;
    private static final long WORKITEM_ID = 50063L;
    private static final String SECRET = "test-secret-key-that-is-long-enough-32bytes!";

    @Test
    void personalLongLivedCredentialMintsScopedToken() {
        WorkitemCliUploadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");

        WorkitemCliUploadTokenVO vo = service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID);

        assertTrue(vo.getToken().startsWith(WorkitemCliUploadTokenService.TOKEN_PREFIX));
        assertEquals("Bearer", vo.getTokenType());
        assertEquals(1800, vo.getExpiresInSeconds());
        long expiresAt = Instant.parse(vo.getExpiresAt()).getEpochSecond();
        long expected = Instant.now().getEpochSecond() + 1800;
        assertTrue(Math.abs(expiresAt - expected) <= 5);
        assertEquals("https://daily.auto-wonder.example.com", vo.getServerUrl());
        assertEquals("0.2.130", vo.getRuntimeVersion());
        assertEquals("AUTOWONDER_UPLOAD_TOKEN", vo.getTokenEnvName());
        assertEquals(java.util.List.of(".md", ".markdown", ".png", ".jpg", ".jpeg", ".webp"),
                vo.getSupportedExtensions());
        assertEquals(10, vo.getMaxFiles());
        assertEquals(5L * 1024 * 1024, vo.getMaxFileSizeBytes());
        assertEquals(20L * 1024 * 1024, vo.getMaxTotalSizeBytes());
        assertFalse(vo.getToken().contains(SECRET));
    }

    @Test
    void commandsUseConfiguredDeploymentValuesAndRepeatableFiles() {
        WorkitemCliUploadTokenService service = service("http://autowonder.internal.example.com:8080", "0.9.9-rc.1");

        WorkitemCliUploadTokenVO vo = service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID);

        for (String command : new String[]{vo.getCommand(), vo.getPowershellCommand()}) {
            assertTrue(command.contains("autowonder@0.9.9-rc.1"), command);
            assertTrue(command.contains("http://autowonder.internal.example.com:8080"), command);
            assertTrue(command.contains("--workitem-id " + WORKITEM_ID), command);
            assertTrue(command.contains("--file <filepath-1>"), command);
            assertTrue(command.contains("--file <filepath-2>"), command);
            assertTrue(command.contains("--file <images-1>"), command);
            assertTrue(command.contains("--json"), command);
            assertFalse(command.contains("autowonder@latest"));
            assertFalse(command.contains("auto-wonder.alibaba.net"));
        }
        assertTrue(vo.getCommand().startsWith("export AUTOWONDER_UPLOAD_TOKEN='awupload_"));
        assertTrue(vo.getPowershellCommand().startsWith("$env:AUTOWONDER_UPLOAD_TOKEN='awupload_"));
        assertEquals(
                "npx -y autowonder@0.9.9-rc.1 workitem upload"
                        + " --server-url http://autowonder.internal.example.com:8080"
                        + " --workitem-id <workitem-id>"
                        + " --file <filepath-1> --file <filepath-2> --file <images-1> --json",
                service.commandTemplate());
    }

    @Test
    void dispatchCredentialCannotMintEvenWithWriteMembership() {
        WorkitemCliUploadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");

        BizException e = assertThrows(BizException.class, () -> service.mint(
                McpAccessTokenService.CredentialType.DISPATCH, USER_ID, WORKITEM_ID));
        assertEquals("10403", e.getCode());
    }

    @Test
    void conversationCredentialCannotMintEvenWithWriteMembership() {
        WorkitemCliUploadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");

        BizException e = assertThrows(BizException.class, () -> service.mint(
                McpAccessTokenService.CredentialType.CONVERSATION, USER_ID, WORKITEM_ID));
        assertEquals("10403", e.getCode());
    }

    @Test
    void missingWorkitemIsRejected() {
        WorkitemCliUploadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID));
        assertEquals("13003", e.getCode());
    }

    @Test
    void workitemFromAnotherOrgCannotBeUsed() {
        WorkitemCliUploadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");
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
    void readOnlyMembershipCannotMint() {
        WorkitemCliUploadTokenService service = serviceWithMember(member("READ_ONLY"));

        BizException e = assertThrows(BizException.class, () -> service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID));
        assertEquals("10403", e.getCode());
    }

    @Test
    void inactiveOrMissingMembershipCannotMint() {
        for (WorkspaceMemberDO member : new WorkspaceMemberDO[]{
                null, member("READ_WRITE", 1, 0), member("READ_WRITE", 0, 1)}) {
            WorkitemCliUploadTokenService service = serviceWithMember(member);
            BizException e = assertThrows(BizException.class, () -> service.mint(
                    McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID));
            assertEquals("10403", e.getCode());
        }
    }

    @Test
    void adminMembershipCanMint() {
        WorkitemCliUploadTokenService service = serviceWithMember(member("ADMIN"));

        WorkitemCliUploadTokenVO vo = service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID);

        assertTrue(vo.getToken().startsWith(WorkitemCliUploadTokenService.TOKEN_PREFIX));
    }

    @Test
    void mintedTokenRoundTripsThroughAuthenticate() {
        WorkitemCliUploadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");
        WorkitemCliUploadTokenVO vo = service.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, WORKITEM_ID);

        assertEquals(USER_ID, service.authenticate(vo.getToken()));
    }

    @Test
    void authenticateRejectsInvalidTokens() {
        WorkitemCliUploadTokenService service = service("https://daily.auto-wonder.example.com", "0.2.130");
        JwtService jwt = jwtService();
        String wrongPurpose = WorkitemCliUploadTokenService.TOKEN_PREFIX
                + jwt.signUserPurpose(USER_ID, "dispatch-mcp", 1800);
        String expired = WorkitemCliUploadTokenService.TOKEN_PREFIX
                + jwt.signUserPurpose(USER_ID, WorkitemCliUploadTokenService.PURPOSE, -1);
        String valid = WorkitemCliUploadTokenService.TOKEN_PREFIX
                + jwt.signUserPurpose(USER_ID, WorkitemCliUploadTokenService.PURPOSE, 1800);
        String tampered = valid.substring(0, valid.length() - 4) + "AAAA";

        for (String token : new String[]{null, "", "awdispatch_x", wrongPurpose, expired, tampered}) {
            BizException e = assertThrows(BizException.class, () -> service.authenticate(token));
            assertEquals("10401", e.getCode());
        }
    }

    @Test
    void commandTemplateQuotesServerUrlWithTrailingSlashNormalized() {
        WorkitemCliUploadTokenService service = service("https://autowonder.example.com/", "0.2.130");

        assertTrue(service.commandTemplate().contains("--server-url https://autowonder.example.com "));
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

    private WorkitemCliUploadTokenService service(String baseUrl, String runtimeVersion) {
        return serviceWithMember(baseUrl, runtimeVersion, member("READ_WRITE"));
    }

    private WorkitemCliUploadTokenService serviceWithMember(WorkspaceMemberDO member) {
        return serviceWithMember("https://daily.auto-wonder.example.com", "0.2.130", member);
    }

    private WorkitemCliUploadTokenService serviceWithMember(
            String baseUrl, String runtimeVersion, WorkspaceMemberDO member) {
        when(workitemDao.findById(WORKITEM_ID)).thenReturn(workitem());
        when(workspaceMemberDao.findByWorkspaceAndUser(TENANT_ID, USER_ID)).thenReturn(member);
        PlatformBrandingDao brandingDao = mock(PlatformBrandingDao.class);
        PlatformBrandingService branding = new PlatformBrandingService(
                brandingDao, new InMemoryObjectStorage(), new OssProperties(),
                baseUrl, runtimeVersion, "x.x.x");
        return new WorkitemCliUploadTokenService(jwtService(), workitemDao, workspaceMemberDao, branding);
    }

    private static WorkitemDO workitem() {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(WORKITEM_ID);
        workitem.setTenantId(TENANT_ID);
        return workitem;
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
