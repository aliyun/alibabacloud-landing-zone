package com.aliyun.autowonder.notification;

import org.junit.jupiter.api.Test;

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
        assertThat(WorkspaceAccessNotifyText.accessLevelLabel("READ_ONLY")).isEqualTo("只读");
        assertThat(WorkspaceAccessNotifyText.accessLevelLabel("READ_WRITE")).isEqualTo("读写");
        assertThat(WorkspaceAccessNotifyText.accessLevelLabel("ADMIN")).isEqualTo("管理员");
        assertThat(WorkspaceAccessNotifyText.accessLevelLabel(null)).isEmpty();
        assertThat(WorkspaceAccessNotifyText.accessLevelLabel("SUPER")).isEqualTo("SUPER");
    }
}
