package com.aliyun.autowonder.mcp;
import com.aliyun.autowonder.auth.jwt.*;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.workitem.*;
import com.aliyun.autowonder.workspace.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ConversationCliCredentialTest {
    WorkitemDao items=mock(WorkitemDao.class);
    WorkspaceMemberDao members=mock(WorkspaceMemberDao.class);
    ConversationMcpTokenService origins=mock(ConversationMcpTokenService.class);
    PlatformBrandingService branding=mock(PlatformBrandingService.class);
    McpAccessTokenService.ExecutionScope scope=new McpAccessTokenService.ExecutionScope(10,60,1,50,30,40);
    McpAccessTokenService.Principal principal=new McpAccessTokenService.Principal(20L,70L,10L,WorkspaceAccessLevel.READ_WRITE,McpAccessTokenService.CredentialType.CONVERSATION,scope);
    WorkitemCliUploadTokenService uploads;
    WorkitemCliDownloadTokenService downloads;
    @BeforeEach void setup() {
        JwtProperties properties=new JwtProperties(mock(org.springframework.core.env.Environment.class));
        properties.setSecret("test-secret-test-secret-test-secret-test-secret");
        JwtService jwt=new JwtService(properties);
        uploads=new WorkitemCliUploadTokenService(jwt,items,members,branding,origins);
        downloads=new WorkitemCliDownloadTokenService(jwt,items,members,branding,origins);
        WorkitemDO item=new WorkitemDO(); item.setId(80L); item.setTenantId(20L); when(items.findById(80L)).thenReturn(item);
        WorkspaceMemberDO member=new WorkspaceMemberDO(); member.setStatus(0); member.setIsDeleted(0); member.setAccessLevel("READ_WRITE");
        when(members.findByWorkspaceAndUser(20L,70L)).thenReturn(member);
        when(branding.effectivePublicBaseUrl()).thenReturn("https://example.test"); when(branding.recommendedRuntimeVersion()).thenReturn("1");
        when(origins.validateExecution(70L,20L,scope)).thenReturn(principal);
    }
    @Test void uploadChildIsResourceAndTurnBoundAndCannotUseScheduledLegacyRoute() {
        var token=uploads.mint(principal,80L).getToken();
        assertEquals(scope,uploads.authenticateForWorkitem(token,80L).executionScope());
        assertThrows(BizException.class,()->uploads.authenticate(token));
        assertThrows(BizException.class,()->uploads.authenticateForWorkitem(token,81L));
        when(origins.validateExecution(70L,20L,scope)).thenThrow(new BizException(com.aliyun.autowonder.common.error.ErrorCode.UNAUTHORIZED));
        assertThrows(BizException.class,()->uploads.authenticateForWorkitem(token,80L));
    }
    @Test void downloadChildIsResourceAndTurnBound() {
        var token=downloads.mint(principal,80L).getToken();
        assertEquals(scope,downloads.authenticateForWorkitem(token,80L).executionScope());
        assertThrows(BizException.class,()->downloads.authenticate(token));
        assertThrows(BizException.class,()->downloads.authenticateForWorkitem(token,81L));
        when(origins.validateExecution(70L,20L,scope)).thenThrow(new BizException(com.aliyun.autowonder.common.error.ErrorCode.UNAUTHORIZED));
        assertThrows(BizException.class,()->downloads.authenticateForWorkitem(token,80L));
    }
    @Test void liveRoleRemovalAndTenantMismatchRejectBothChildren() {
        var upload=uploads.mint(principal,80L).getToken(); var download=downloads.mint(principal,80L).getToken();
        when(members.findByWorkspaceAndUser(20L,70L)).thenReturn(null);
        assertThrows(BizException.class,()->uploads.authenticateForWorkitem(upload,80L));
        assertThrows(BizException.class,()->downloads.authenticateForWorkitem(download,80L));
        WorkitemDO other=new WorkitemDO(); other.setTenantId(21L); when(items.findById(80L)).thenReturn(other);
        assertThrows(BizException.class,()->uploads.authenticateForWorkitem(upload,80L));
        assertThrows(BizException.class,()->downloads.authenticateForWorkitem(download,80L));
    }
    @Test void rejectedChildAdmissionStillAuditsTheSignedOriginalActor() {
        var audit=mock(com.aliyun.autowonder.audit.AuditLogService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(uploads,"executionAudit",new ConversationCredentialAuditService(audit));
        String token=uploads.mint(principal,80L).getToken();
        assertThrows(BizException.class,()->uploads.authenticateForWorkitem(token,81L));
        var records=org.mockito.ArgumentCaptor.forClass(com.aliyun.autowonder.audit.AuditLogRecord.class);
        verify(audit).record(records.capture());
        assertEquals(70L,records.getValue().getActorId());
        assertEquals(60L,records.getValue().getDetail().get("turnId"));
        assertEquals(false,records.getValue().getDetail().get("success"));
        assertFalse(records.getValue().getDetail().toString().contains(token));
    }
    @Test void actualUploadAndDownloadAuditCompletionWithScope() throws Exception {
        var audit=mock(com.aliyun.autowonder.audit.AuditLogService.class);
        var helper=new ConversationCredentialAuditService(audit);
        org.springframework.test.util.ReflectionTestUtils.setField(uploads,"executionAudit",helper);
        org.springframework.test.util.ReflectionTestUtils.setField(downloads,"executionAudit",helper);
        var documents=mock(com.aliyun.autowonder.artifact.RequirementDocumentService.class);
        when(documents.uploadCli(eq(80L),any(),eq(20L),eq(70L))).thenReturn(java.util.List.of());
        var uploadController=new com.aliyun.autowonder.artifact.WorkitemCliUploadController(uploads,items,documents);
        var downloadController=new com.aliyun.autowonder.artifact.WorkitemCliDownloadController(downloads,items,documents);
        assertEquals(200,uploadController.upload(80L,new org.springframework.web.multipart.MultipartFile[0],"Bearer "+uploads.mint(principal,80L).getToken()).getStatusCodeValue());
        assertEquals(200,downloadController.index(80L,"Bearer "+downloads.mint(principal,80L).getToken()).getStatusCodeValue());
        var records=org.mockito.ArgumentCaptor.forClass(com.aliyun.autowonder.audit.AuditLogRecord.class);
        verify(audit,times(2)).record(records.capture());
        for(var record:records.getAllValues()) {
            assertEquals(70L,record.getActorId()); assertEquals(30L,record.getDetail().get("executingAgentId"));
            assertEquals(60L,record.getDetail().get("turnId")); assertEquals(true,record.getDetail().get("success"));
        }
    }
    @Test void actualCliFailureIsAuditedWithoutExceptionPayload() throws Exception {
        var audit=mock(com.aliyun.autowonder.audit.AuditLogService.class);
        var helper=new ConversationCredentialAuditService(audit);
        org.springframework.test.util.ReflectionTestUtils.setField(uploads,"executionAudit",helper);
        org.springframework.test.util.ReflectionTestUtils.setField(downloads,"executionAudit",helper);
        var documents=mock(com.aliyun.autowonder.artifact.RequirementDocumentService.class);
        when(documents.uploadCli(eq(80L),any(),eq(20L),eq(70L))).thenThrow(new IllegalStateException("private diagnostic"));
        when(documents.list(80L,20L)).thenThrow(new IllegalStateException("private diagnostic"));
        var uploadController=new com.aliyun.autowonder.artifact.WorkitemCliUploadController(uploads,items,documents);
        var downloadController=new com.aliyun.autowonder.artifact.WorkitemCliDownloadController(downloads,items,documents);
        String upload=uploads.mint(principal,80L).getToken(), download=downloads.mint(principal,80L).getToken();
        assertThrows(IllegalStateException.class,()->uploadController.upload(80L,new org.springframework.web.multipart.MultipartFile[0],"Bearer "+upload));
        assertThrows(IllegalStateException.class,()->downloadController.index(80L,"Bearer "+download));
        var records=org.mockito.ArgumentCaptor.forClass(com.aliyun.autowonder.audit.AuditLogRecord.class);
        verify(audit,times(2)).record(records.capture());
        for(var record:records.getAllValues()) {
            assertEquals(70L,record.getActorId()); assertEquals(false,record.getDetail().get("success"));
            assertFalse(record.getDetail().toString().contains("private diagnostic"));
        }
    }
    @Test void readOnlyCeilingCannotMintUpload() {
        principal=new McpAccessTokenService.Principal(20L,70L,10L,WorkspaceAccessLevel.READ_ONLY,McpAccessTokenService.CredentialType.CONVERSATION,scope);
        when(origins.validateExecution(70L,20L,scope)).thenReturn(principal);
        assertThrows(BizException.class,()->uploads.mint(principal,80L));
    }
}
