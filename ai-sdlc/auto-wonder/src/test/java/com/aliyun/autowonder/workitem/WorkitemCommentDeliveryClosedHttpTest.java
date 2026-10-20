package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of commenting with an @agent on a closed delivery. */
class WorkitemCommentDeliveryClosedHttpTest {
    private static final String REQUEST = "{\"contentMd\":\"@AW全栈开发 看下\",\"targetAgentIds\":[40013]}";

    private WorkitemService workitemService;
    private GuidanceService guidanceService;
    private MockMvc http;

    @BeforeEach
    void setUp() {
        workitemService = mock(WorkitemService.class);
        guidanceService = mock(GuidanceService.class);
        http = MockMvcBuilders.standaloneSetup(new WorkitemController(workitemService, guidanceService,
                        mock(WorkitemWatcherService.class), mock(WorkitemOwningWorkspaceResolver.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setLocaleResolver(new com.aliyun.autowonder.auth.filter.AuthFilterConfig().localeResolver())
                .build();
        var context = AutoWonderContext.get();
        context.setCurrentWorkspaceId(100L);
        context.setUserId(7L);
        context.setWorkspaceAccessLevel(WorkspaceAccessLevel.READ_WRITE);
    }

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh-CN", "en-US"})
    void closedDeliverySurfaces409WithItsOwnBilingualReasonAndSaveResult(String language) throws Exception {
        stubCommentSavedThen(closedDeliveryRejection());

        http.perform(post("/api/workitems/50/comments").header("Accept-Language", language)
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("13024"))
                .andExpect(jsonPath("$.message").value(language.equals("en-US")
                        ? "Comment not sent: delivery for this work item is closed, so the mentioned "
                                + "digital worker cannot start. Reopen delivery and retry, or remove "
                                + "the mention and send a normal comment."
                        : "评论未发送：该工单的交付已关闭，无法启动被 @ 的数字员工。"
                                + "请先重新打开交付后重试，或移除数字员工 @ 后发送普通评论。"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh-CN", "en-US"})
    void otherCommentConflictsKeepTheirOwnCodeAndReason(String language) throws Exception {
        stubCommentSavedThen(BizException.keyed(ErrorCode.CONFLICT, "dispatch.recovery.interaction",
                new Object[0], "该 Worker 正在处理评论交互，请稍后再试"));

        http.perform(post("/api/workitems/50/comments").header("Accept-Language", language)
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("10409"))
                .andExpect(jsonPath("$.message").value(language.equals("en-US")
                        ? "This Worker is processing a comment interaction. Try again later."
                        : "该 Worker 正在处理评论交互，请稍后再试"));
    }

    @Test
    void plainCommentWithoutAgentsKeepsTheSuccessContract() throws Exception {
        CommentVO comment = new CommentVO();
        comment.setId(600L);
        when(workitemService.addComment(eq(50L), anyString(), isNull(), eq(100L), eq(7L)))
                .thenReturn(comment);

        http.perform(post("/api/workitems/50/comments").contentType("application/json")
                        .content("{\"contentMd\":\"普通评论\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(600));
    }

    private void stubCommentSavedThen(RuntimeException guidanceFailure) {
        CommentVO comment = new CommentVO();
        comment.setId(600L);
        when(workitemService.addComment(eq(50L), anyString(), isNull(), eq(100L), eq(7L)))
                .thenReturn(comment);
        doThrow(guidanceFailure).when(guidanceService).createForComment(
                eq(100L), eq(50L), eq(600L), anyString(), anyList(), eq(7L));
    }

    private static BizException closedDeliveryRejection() {
        return BizException.keyed(ErrorCode.WORKITEM_DELIVERY_CLOSED,
                "workitem.comment.delivery_closed", new Object[0], "交付已关闭，评论未发送，无法启动数字员工");
    }
}
