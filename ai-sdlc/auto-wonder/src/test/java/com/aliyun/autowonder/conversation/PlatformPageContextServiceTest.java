package com.aliyun.autowonder.conversation;
import com.aliyun.autowonder.mcp.McpToolService;
import com.aliyun.autowonder.mcp.McpAccessTokenService;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.Map;
class PlatformPageContextServiceTest {
    @Test void extractsBoundResourceUnderTheUserReadOnlyIdentity() {
        var tools=mock(McpToolService.class);
        when(tools.call(any(),eq("autowonder.get_workitem"),any())).thenAnswer(inv->{
            McpAccessTokenService.Principal identity=inv.getArgument(0);
            assertEquals(1L,identity.workspaceId());assertEquals(3L,identity.userId());assertEquals(WorkspaceAccessLevel.READ_ONLY,identity.accessLevel());
            return Map.of("id",128,"title","Refund approval","contentMd","Details","token","secret");});
        String snapshot=new PlatformPageContextService(tools).snapshot(1,3,2,"/workitems/128");
        assertTrue(snapshot.contains("128"));assertTrue(snapshot.contains("Refund approval"));assertFalse(snapshot.contains("secret"));
    }
    @Test void rejectsArbitraryUrlsAndPropagatesResourcePermissionDenials() {
        var tools=mock(McpToolService.class);var service=new PlatformPageContextService(tools);
        assertNull(service.snapshot(1,3,2,"https://private.invalid"));verifyNoInteractions(tools);
        when(tools.call(any(),anyString(),any())).thenThrow(new BizException(ErrorCode.NO_PERMISSION));
        assertThrows(BizException.class,()->service.snapshot(1,3,2,"/workitems/128"));
    }
}
