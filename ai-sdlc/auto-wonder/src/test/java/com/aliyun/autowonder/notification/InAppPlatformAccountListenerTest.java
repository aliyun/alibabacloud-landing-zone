package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.user.event.UserRegistrationReviewRequestedEvent;
import com.aliyun.autowonder.user.event.WorkspaceCreationAccessRequestedEvent;
import com.aliyun.autowonder.user.event.WorkspaceCreationPermissionGrantedEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InAppPlatformAccountListenerTest {

    private final NotifyService notifyService = mock(NotifyService.class);
    private final UserDao userDao = mock(UserDao.class);
    private final InAppPlatformAccountListener listener =
            new InAppPlatformAccountListener(notifyService, userDao);

    private static UserDO admin(long id) {
        UserDO user = new UserDO();
        user.setId(id);
        return user;
    }

    @Test
    void registrationReviewNotifiesEveryAvailableAdministratorWithPlatformScope() {
        when(userDao.listAvailablePlatformAdmins()).thenReturn(List.of(admin(1L), admin(2L)));

        listener.onRegistrationReviewRequested(new UserRegistrationReviewRequestedEvent(9L, "小林", "lin"));

        verify(notifyService, times(2)).notify(any(NotifyEvent.class));
    }

    @Test
    void registrationReviewWithoutAdminsStaysQueuedWithoutNotification() {
        when(userDao.listAvailablePlatformAdmins()).thenReturn(List.of());
        listener.onRegistrationReviewRequested(new UserRegistrationReviewRequestedEvent(9L, "小林", "lin"));
        verify(notifyService, never()).notify(any());
    }

    @Test
    void oneFailingRecipientDoesNotDropTheOthers() {
        when(userDao.listAvailablePlatformAdmins()).thenReturn(List.of(admin(1L), admin(2L)));
        doThrow(new IllegalStateException("db down")).doNothing().when(notifyService).notify(any());

        listener.onRegistrationReviewRequested(new UserRegistrationReviewRequestedEvent(9L, "小林", "lin"));

        verify(notifyService, times(2)).notify(any(NotifyEvent.class));
    }

    @Test
    void workspaceCreationRequestUsesApplicantDetailDeepLink() {
        listener.onWorkspaceCreationRequested(
                new WorkspaceCreationAccessRequestedEvent(50L, 9L, "阿杰", "jie", List.of(1L)));
        verify(notifyService).notify(argThat(event -> event != null
                && NotificationScope.PLATFORM.equals(event.getScope())
                && event.getTenantId() == 0L
                && event.getLink().equals("/workspaces/branding?tab=user-management&userId=9")));
    }

    @Test
    void grantedResultIsSentOnlyToTheApplicant() {
        listener.onWorkspaceCreationGranted(new WorkspaceCreationPermissionGrantedEvent(9L));
        verify(notifyService).notify(argThat(event -> event != null
                && event.getRecipientIds().equals(List.of(9L))
                && NotificationScope.PLATFORM.equals(event.getScope())));
    }
}
