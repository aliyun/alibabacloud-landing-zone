package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledRunMentionCandidateVO;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskRunVO;
import com.aliyun.autowonder.workitem.WorkitemService;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledTaskRunControllerTest {
    @AfterEach
    void clearContext() {
        AutoWonderContext.destroy();
    }

    private ScheduledTaskRunController newController(ScheduledTaskRunDao runDao, ScheduledTaskRunService runService,
            ScheduledTaskRunDispatchControlService control) {
        return newController(runDao, runService, control, mock(ScheduledTaskRunCommentService.class),
                mock(ScheduledTaskRunParticipantService.class));
    }

    private ScheduledTaskRunController newController(ScheduledTaskRunDao runDao, ScheduledTaskRunService runService,
            ScheduledTaskRunDispatchControlService control, ScheduledTaskRunCommentService commentService,
            ScheduledTaskRunParticipantService participantService) {
        return new ScheduledTaskRunController(runDao, runService, mock(ScheduledTaskRunOrchestrator.class),
                commentService, mock(ArtifactService.class),
                mock(DispatchDao.class), mock(DispatchRuntimeEventDao.class), mock(WorkitemService.class),
                control, participantService,
                mock(ScheduledTaskRunDeliveryProgressService.class));
    }

    private ScheduledTaskRunDO runningRun() {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(9L); run.setWorkspaceId(1L); run.setOwnerId(7L);
        run.setStatus("RUNNING"); run.setVersion(3);
        return run;
    }

    private void assumeUser(long workspaceId, long userId) {
        AutoWonderContext context = AutoWonderContext.get();
        context.setCurrentWorkspaceId(workspaceId);
        context.setUserId(userId);
        AutoWonderContext.setContext(context);
    }

    @Test
    void commentsRequireWriteAccess() {
        Method method = java.util.Arrays.stream(ScheduledTaskRunController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("comment")).findFirst().orElseThrow();
        RequireWorkspaceAccess access = AnnotatedElementUtils.findMergedAnnotation(method, RequireWorkspaceAccess.class);
        assertNotNull(access);
        assertEquals(WorkspaceAccessLevel.READ_WRITE, access.value());
    }

    @Test
    void commentForwardsExplicitAgentTargetsToCommentService() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskRunCommentService comments = mock(ScheduledTaskRunCommentService.class);
        CommentVO comment = new CommentVO();
        when(comments.addHumanComment(1L, 9L, 7L, "请复核失败日志", List.of(21L), List.of(10000L)))
                .thenReturn(comment);
        assumeUser(1L, 7L);
        ScheduledTaskRunController.RunCommentRequest request = new ScheduledTaskRunController.RunCommentRequest();
        request.setContentMd("请复核失败日志");
        request.setTargetAgentIds(List.of(21L));
        request.setTargetHumanIds(List.of(10000L));

        Result<CommentVO> result = newController(runDao, mock(ScheduledTaskRunService.class),
                mock(ScheduledTaskRunDispatchControlService.class), comments,
                mock(ScheduledTaskRunParticipantService.class)).comment(9L, request);

        assertTrue(result.isSuccess());
        verify(comments).addHumanComment(1L, 9L, 7L, "请复核失败日志", List.of(21L), List.of(10000L));
    }

    @Test
    void commentWithNullRequestForwardsNullContentAndTargets() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskRunCommentService comments = mock(ScheduledTaskRunCommentService.class);
        CommentVO comment = new CommentVO();
        when(comments.addHumanComment(1L, 9L, 7L, null, null, null)).thenReturn(comment);
        assumeUser(1L, 7L);

        Result<CommentVO> result = newController(runDao, mock(ScheduledTaskRunService.class),
                mock(ScheduledTaskRunDispatchControlService.class), comments,
                mock(ScheduledTaskRunParticipantService.class)).comment(9L, null);

        assertTrue(result.isSuccess());
        verify(comments).addHumanComment(1L, 9L, 7L, null, null, null);
    }

    @Test
    void mentionCandidatesDelegateToParticipantService() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskRunParticipantService participants = mock(ScheduledTaskRunParticipantService.class);
        ScheduledTaskRunDO run = runningRun();
        when(runDao.findById(1L, 9L)).thenReturn(run);
        List<ScheduledRunMentionCandidateVO> candidates = List.of(new ScheduledRunMentionCandidateVO());
        when(participants.getMentionCandidates(1L, run, "缺", 30)).thenReturn(candidates);
        assumeUser(1L, 7L);

        Result<List<ScheduledRunMentionCandidateVO>> result = newController(runDao,
                mock(ScheduledTaskRunService.class), mock(ScheduledTaskRunDispatchControlService.class),
                mock(ScheduledTaskRunCommentService.class), participants).mentionCandidates(9L, "缺", 30);

        assertTrue(result.isSuccess());
        assertEquals(candidates, result.getData());
        verify(participants).getMentionCandidates(1L, run, "缺", 30);
    }

    @Test
    void mentionCandidatesRejectMissingRun() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        when(runDao.findById(1L, 404L)).thenReturn(null);
        assumeUser(1L, 7L);

        assertThrows(BizException.class, () -> newController(runDao, mock(ScheduledTaskRunService.class),
                mock(ScheduledTaskRunDispatchControlService.class), mock(ScheduledTaskRunCommentService.class),
                mock(ScheduledTaskRunParticipantService.class)).mentionCandidates(404L, null, 50));
    }

    @Test
    void forceCancelSkipsPauseHandshakeAndTerminatesRunImmediately() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskRunService runService = mock(ScheduledTaskRunService.class);
        ScheduledTaskRunDispatchControlService control = mock(ScheduledTaskRunDispatchControlService.class);
        ScheduledTaskRunDO run = runningRun();
        when(runDao.findById(1L, 9L)).thenReturn(run);
        when(runService.markCancelIntent(run, 7L)).thenAnswer(invocation -> {
            ScheduledTaskRunDO target = invocation.getArgument(0);
            target.setVersion(target.getVersion() + 1);
            return true;
        });
        ScheduledTaskRunDO canceled = runningRun();
        canceled.setStatus("CANCELED"); canceled.setVersion(5);
        when(runService.transition(1L, 9L, 4, "CANCELED", 7L)).thenReturn(canceled);
        assumeUser(1L, 7L);

        Result<ScheduledTaskRunVO> result = newController(runDao, runService, control).cancel(9L, 3, true);

        assertTrue(result.isSuccess());
        assertEquals("CANCELED", result.getData().getStatus());
        verify(control).forceCancelActive(1L, 9L, 7L);
        verify(control, never()).pauseActive(anyLong(), anyLong(), anyLong(), anyBoolean());
        verify(runService).transition(1L, 9L, 4, "CANCELED", 7L);
    }

    @Test
    void gracefulCancelStillWaitsForExecutorPauseAcknowledgment() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskRunService runService = mock(ScheduledTaskRunService.class);
        ScheduledTaskRunDispatchControlService control = mock(ScheduledTaskRunDispatchControlService.class);
        ScheduledTaskRunDO run = runningRun();
        when(runDao.findById(1L, 9L)).thenReturn(run);
        when(runService.markCancelIntent(run, 7L)).thenAnswer(invocation -> {
            ScheduledTaskRunDO target = invocation.getArgument(0);
            target.setVersion(target.getVersion() + 1);
            return true;
        });
        when(control.pauseActive(1L, 9L, 7L, true)).thenReturn(true);
        ScheduledTaskRunDO paused = runningRun();
        paused.setStatus("PAUSED"); paused.setVersion(5);
        when(runService.transition(1L, 9L, 4, "PAUSED", 7L)).thenReturn(paused);
        assumeUser(1L, 7L);

        Result<ScheduledTaskRunVO> result = newController(runDao, runService, control).cancel(9L, 3, false);

        assertTrue(result.isSuccess());
        verify(control).pauseActive(1L, 9L, 7L, true);
        verify(control, never()).forceCancelActive(anyLong(), anyLong(), anyLong());
        verify(runService).transition(1L, 9L, 4, "PAUSED", 7L);
    }
}
