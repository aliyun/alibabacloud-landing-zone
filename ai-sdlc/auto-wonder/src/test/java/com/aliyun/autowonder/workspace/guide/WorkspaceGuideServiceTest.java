package com.aliyun.autowonder.workspace.guide;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.mcp.PlatformSkillCatalog;
import com.aliyun.autowonder.mcp.dto.PlatformSkillVO;
import com.aliyun.autowonder.user.UserSettingService;
import com.aliyun.autowonder.user.dto.UserSettingVO;
import com.aliyun.autowonder.workspace.guide.dto.SkipWorkspaceGuideRequest;
import com.aliyun.autowonder.workspace.guide.dto.UpdateWorkspaceGuideRequest;
import com.aliyun.autowonder.workspace.guide.dto.WorkspaceGuideVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceGuideServiceTest {

    private WorkspaceGuideProgressDao guideDao;
    private UserSettingService userSettingService;
    private PlatformSkillCatalog platformSkillCatalog;
    private WorkspaceGuideService service;

    @BeforeEach
    void setUp() {
        guideDao = mock(WorkspaceGuideProgressDao.class);
        userSettingService = mock(UserSettingService.class);
        platformSkillCatalog = mock(PlatformSkillCatalog.class);
        when(platformSkillCatalog.list()).thenReturn(List.of());
        when(userSettingService.get(anyLong(), anyString())).thenReturn(setting(null));
        service = new WorkspaceGuideService(guideDao, userSettingService, platformSkillCatalog);
    }

    @Test
    void registersPendingProgressForWorkspaceCreator() {
        service.registerEligibility(10L, 7L);

        ArgumentCaptor<WorkspaceGuideProgressDO> row = ArgumentCaptor.forClass(WorkspaceGuideProgressDO.class);
        verify(guideDao).insertIfAbsent(row.capture());
        assertEquals(10L, row.getValue().getWorkspaceId());
        assertEquals(7L, row.getValue().getUserId());
        assertEquals("PENDING", row.getValue().getStatus());
        assertEquals(1, row.getValue().getStage());
        assertEquals(0, row.getValue().getSubstep());
        assertEquals(7L, row.getValue().getCreatorId());
        assertEquals(7L, row.getValue().getModifierId());
    }

    @Test
    void reportsNoEligibilityWhenNoRecordExists() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(null);

        WorkspaceGuideVO vo = service.get(10L, 7L);

        assertFalse(vo.isEligible());
        assertNull(vo.getStatus());
        assertNull(vo.getVersion());
    }

    @Test
    void historicalWorkspaceDoesNotDependOnAccountPreference() {
        when(userSettingService.get(7L, WorkspaceGuideService.DISABLE_SETTING_KEY))
                .thenThrow(new IllegalStateException("preference read failed"));

        assertFalse(service.get(10L, 7L).isEligible());
    }

    @Test
    void returnsStoredProgressAndAccountPreference() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(row("IN_PROGRESS", 3, 2, 4));
        when(userSettingService.get(7L, WorkspaceGuideService.DISABLE_SETTING_KEY))
                .thenReturn(setting("true"));

        WorkspaceGuideVO vo = service.get(10L, 7L);

        assertTrue(vo.isEligible());
        assertTrue(vo.isGloballyDisabled());
        assertFalse(vo.isCapabilityReady());
        assertEquals("IN_PROGRESS", vo.getStatus());
        assertEquals(3, vo.getStage());
        assertEquals(2, vo.getSubstep());
        assertEquals(4, vo.getVersion());
        assertEquals(77L, vo.getConversationId());
        assertEquals("message-1", vo.getClientMessageId());
    }

    @Test
    void doesNotTreatOtherFormatsAsGloballyDisabled() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(row("PENDING", 1, 0, 0));
        when(userSettingService.get(7L, WorkspaceGuideService.DISABLE_SETTING_KEY))
                .thenReturn(setting("false"));

        assertFalse(service.get(10L, 7L).isGloballyDisabled());
    }

    @Test
    void reportsCapabilityOnlyWhenSkillCatalogContainsInitializationSkill() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(null);
        when(platformSkillCatalog.list()).thenReturn(List.of(
                new PlatformSkillVO(WorkspaceGuideService.INITIALIZATION_SKILL_ID, "CODEX_SKILL", "init", "", "{}")));

        assertTrue(service.get(10L, 7L).isCapabilityReady());
    }

    @Test
    void treatsMismatchedOwnerRowAsNotEligible() {
        WorkspaceGuideProgressDO otherUser = row("PENDING", 1, 0, 0);
        otherUser.setUserId(99L);
        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(otherUser);

        assertFalse(service.get(10L, 7L).isEligible());
    }

    @Test
    void advancesToNextSubstepAndReturnsFreshProgress() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L))
                .thenReturn(row("IN_PROGRESS", 1, 1, 2))
                .thenReturn(row("IN_PROGRESS", 1, 2, 3));
        when(guideDao.updateProgress(anyLong(), anyInt(), anyString(), anyInt(), anyInt(),
                any(), any(), anyLong())).thenReturn(1);

        WorkspaceGuideVO vo = service.advance(10L, 7L, update("IN_PROGRESS", 1, 2, 2));

        assertEquals(2, vo.getSubstep());
        assertEquals(3, vo.getVersion());
        ArgumentCaptor<Long> conversation = ArgumentCaptor.forClass(Long.class);
        verify(guideDao).updateProgress(eq(11L), eq(2), eq("IN_PROGRESS"), eq(1), eq(2),
                conversation.capture(), eq("message-1"), eq(7L));
        assertEquals(77L, conversation.getValue());
    }

    @Test
    void allowsSameTupleMetadataReservationForSending() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L))
                .thenReturn(row("IN_PROGRESS", 3, 0, 5))
                .thenReturn(row("IN_PROGRESS", 3, 0, 6));
        when(guideDao.updateProgress(anyLong(), anyInt(), anyString(), anyInt(), anyInt(),
                any(), any(), anyLong())).thenReturn(1);

        WorkspaceGuideVO vo = service.advance(10L, 7L, update("IN_PROGRESS", 3, 0, 5));

        assertEquals(6, vo.getVersion());
    }

    @Test
    void movesToNextStageOnlyOneStepAtATime() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L))
                .thenReturn(row("IN_PROGRESS", 1, 5, 8))
                .thenReturn(row("IN_PROGRESS", 2, 0, 9));
        when(guideDao.updateProgress(anyLong(), anyInt(), anyString(), anyInt(), anyInt(),
                any(), any(), anyLong())).thenReturn(1);

        WorkspaceGuideVO vo = service.advance(10L, 7L, update("IN_PROGRESS", 2, 0, 8));

        assertEquals(2, vo.getStage());
    }

    @Test
    void rejectsSkippingMoreThanOneStage() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(row("IN_PROGRESS", 1, 0, 3));

        assertStateInvalid(update("IN_PROGRESS", 3, 0, 3));
    }

    @Test
    void rejectsBackwardStageOrSubstep() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(row("IN_PROGRESS", 2, 3, 3));

        assertStateInvalid(update("IN_PROGRESS", 2, 2, 3));
        assertStateInvalid(update("IN_PROGRESS", 1, 5, 3));
    }

    @Test
    void rejectsCompletionBeforeTheLastStage() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(row("IN_PROGRESS", 3, 0, 3));

        assertStateInvalid(update("COMPLETED", 3, 0, 3));
    }

    @Test
    void completesAtTheLastStage() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L))
                .thenReturn(row("IN_PROGRESS", 4, 0, 9))
                .thenReturn(row("COMPLETED", 4, 0, 10));
        when(guideDao.updateProgress(anyLong(), anyInt(), anyString(), anyInt(), anyInt(),
                any(), any(), anyLong())).thenReturn(1);

        assertEquals("COMPLETED", service.advance(10L, 7L, update("COMPLETED", 4, 0, 9)).getStatus());
    }

    @Test
    void rejectsTerminalProgressAndSkipTargets() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(row("SKIPPED", 1, 0, 3));
        assertStateInvalid(update("IN_PROGRESS", 1, 1, 3));

        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(row("IN_PROGRESS", 1, 0, 3));
        assertStateInvalid(update("SKIPPED", 1, 1, 3));
        assertStateInvalid(update("PENDING", 1, 1, 3));
        assertStateInvalid(update("UNKNOWN", 1, 1, 3));
    }

    @Test
    void rejectsOutOfRangeStageAndSubstep() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(row("IN_PROGRESS", 1, 0, 3));

        assertStateInvalid(update("IN_PROGRESS", 0, 0, 3));
        assertStateInvalid(update("IN_PROGRESS", 5, 0, 3));
        assertStateInvalid(update("IN_PROGRESS", 1, 6, 3));
        assertStateInvalid(update("IN_PROGRESS", 1, -1, 3));
    }

    @Test
    void requiresCompleteAdvanceRequest() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(row("IN_PROGRESS", 1, 0, 3));

        BizException missing = assertThrows(BizException.class, () -> service.advance(10L, 7L, null));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), missing.getCode());
        UpdateWorkspaceGuideRequest noVersion = update("IN_PROGRESS", 1, 1, 3);
        noVersion.setVersion(null);
        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.advance(10L, 7L, noVersion)).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class,
                        () -> service.advance(10L, 7L, update("IN_PROGRESS", 1, 1, -1))).getCode());
    }

    @Test
    void mapsZeroUpdatedRowsToVersionConflict() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(row("IN_PROGRESS", 1, 0, 3));
        when(guideDao.updateProgress(anyLong(), anyInt(), anyString(), anyInt(), anyInt(),
                any(), any(), anyLong())).thenReturn(0);

        BizException conflict = assertThrows(BizException.class,
                () -> service.advance(10L, 7L, update("IN_PROGRESS", 1, 1, 3)));
        assertEquals(ErrorCode.WORKSPACE_GUIDE_VERSION_CONFLICT.getCode(), conflict.getCode());
    }

    @Test
    void advanceAndSkipRequireAnOwnedRecord() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(null);

        assertEquals(ErrorCode.WORKSPACE_GUIDE_NOT_ELIGIBLE.getCode(),
                assertThrows(BizException.class,
                        () -> service.advance(10L, 7L, update("IN_PROGRESS", 1, 1, 0))).getCode());
        assertEquals(ErrorCode.WORKSPACE_GUIDE_NOT_ELIGIBLE.getCode(),
                assertThrows(BizException.class, () -> service.skip(10L, 7L, skip(0))).getCode());
        verify(guideDao, never()).updateProgress(anyLong(), anyInt(), anyString(), anyInt(), anyInt(),
                any(), any(), anyLong());
    }

    @Test
    void skipMarksOnlyTheCurrentWorkspaceRecord() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L))
                .thenReturn(row("IN_PROGRESS", 2, 0, 4))
                .thenReturn(row("SKIPPED", 2, 0, 5));
        when(guideDao.updateProgress(anyLong(), anyInt(), anyString(), anyInt(), anyInt(),
                any(), any(), anyLong())).thenReturn(1);

        assertEquals("SKIPPED", service.skip(10L, 7L, skip(4)).getStatus());
        verify(guideDao).updateProgress(11L, 4, "SKIPPED", 2, 0, 77L, "message-1", 7L);
    }

    @Test
    void skipIsIdempotentButRejectsCompletedAndStaleVersions() {
        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(row("SKIPPED", 1, 0, 5));
        assertEquals("SKIPPED", service.skip(10L, 7L, skip(99)).getStatus());
        verify(guideDao, never()).updateProgress(anyLong(), anyInt(), anyString(), anyInt(), anyInt(),
                any(), any(), anyLong());

        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(row("COMPLETED", 4, 0, 5));
        assertEquals(ErrorCode.WORKSPACE_GUIDE_STATE_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.skip(10L, 7L, skip(5))).getCode());

        when(guideDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(row("IN_PROGRESS", 1, 0, 5));
        when(guideDao.updateProgress(anyLong(), anyInt(), anyString(), anyInt(), anyInt(),
                any(), any(), anyLong())).thenReturn(0);
        assertEquals(ErrorCode.WORKSPACE_GUIDE_VERSION_CONFLICT.getCode(),
                assertThrows(BizException.class, () -> service.skip(10L, 7L, skip(5))).getCode());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.skip(10L, 7L, null)).getCode());
    }

    private void assertStateInvalid(UpdateWorkspaceGuideRequest request) {
        assertEquals(ErrorCode.WORKSPACE_GUIDE_STATE_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.advance(10L, 7L, request)).getCode());
    }

    private static UpdateWorkspaceGuideRequest update(String status, int stage, int substep, int version) {
        UpdateWorkspaceGuideRequest request = new UpdateWorkspaceGuideRequest();
        request.setStatus(status);
        request.setStage(stage);
        request.setSubstep(substep);
        request.setVersion(version);
        return request;
    }

    private static SkipWorkspaceGuideRequest skip(int version) {
        SkipWorkspaceGuideRequest request = new SkipWorkspaceGuideRequest();
        request.setVersion(version);
        return request;
    }

    private static WorkspaceGuideProgressDO row(String status, int stage, int substep, int version) {
        WorkspaceGuideProgressDO row = new WorkspaceGuideProgressDO();
        row.setId(11L);
        row.setWorkspaceId(10L);
        row.setUserId(7L);
        row.setStatus(status);
        row.setStage(stage);
        row.setSubstep(substep);
        row.setVersion(version);
        row.setConversationId(77L);
        row.setClientMessageId("message-1");
        return row;
    }

    private static UserSettingVO setting(String valueJson) {
        UserSettingVO setting = new UserSettingVO();
        setting.setKey(WorkspaceGuideService.DISABLE_SETTING_KEY);
        setting.setValueJson(valueJson);
        return setting;
    }
}
