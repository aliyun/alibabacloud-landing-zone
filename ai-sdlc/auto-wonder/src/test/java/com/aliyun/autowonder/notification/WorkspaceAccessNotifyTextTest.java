package com.aliyun.autowonder.notification;

import org.junit.jupiter.api.Test;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceAccessNotifyTextTest {

    @Test
    void safeReturnsEmptyForNullAndPassesThroughOtherwise() {
        assertThat(WorkspaceAccessNotifyText.safe(null)).isEmpty();
        assertThat(WorkspaceAccessNotifyText.safe("星云工坊")).isEqualTo("星云工坊");
    }

    @Test
    void workspaceLabelFallsBackForNullBlankAndWhitespaceNames() {
        assertThat(WorkspaceAccessNotifyText.workspaceLabel(null)).isEqualTo("未命名工作空间");
        assertThat(WorkspaceAccessNotifyText.workspaceLabel("")).isEqualTo("未命名工作空间");
        assertThat(WorkspaceAccessNotifyText.workspaceLabel("   ")).isEqualTo("未命名工作空间");
        assertThat(WorkspaceAccessNotifyText.workspaceLabel("星云工坊")).isEqualTo("星云工坊");
    }

    @Test
    void accessLevelLabelMapsKnownLevelsAndPassesUnknownThrough() {
        assertThat(WorkspaceAccessNotifyText.accessLevelLabel("READ_ONLY")).isEqualTo("只读者");
        assertThat(WorkspaceAccessNotifyText.accessLevelLabel("READ_WRITE")).isEqualTo("读写者");
        assertThat(WorkspaceAccessNotifyText.accessLevelLabel("ADMIN")).isEqualTo("管理员");
        assertThat(WorkspaceAccessNotifyText.accessLevelLabel(null)).isEmpty();
        assertThat(WorkspaceAccessNotifyText.accessLevelLabel("SUPER")).isEqualTo("SUPER");
        assertThat(WorkspaceAccessNotifyText.accessLevelLabel("READ_ONLY", Locale.US)).isEqualTo("Viewer");
        assertThat(WorkspaceAccessNotifyText.accessLevelLabel("READ_WRITE", Locale.US)).isEqualTo("Collaborator");
        assertThat(WorkspaceAccessNotifyText.accessLevelLabel("自定义原文", Locale.US)).isEqualTo("自定义原文");
    }

    @Test
    void reviewLinkCarriesApprovalTabAndRequestingWorkspace() {
        assertThat(WorkspaceAccessNotifyText.reviewLink(100L))
                .isEqualTo("/settings/members?tab=requests&workspaceId=100");
    }
}
