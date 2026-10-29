package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.im.ImChannelRuntimeStatusService;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.dto.GroupChatPublicConfigVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Workspace-visible shared group-chat information; never returns secrets. */
@RestController
@RequestMapping("/api/integrations/channels")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看共享群聊配置",
        actionKey = "access.action.view_shared_group_chat_config")
public class ImChannelPublicController {
    private final PlatformImChannelConfigService configService;
    private final ImChannelRuntimeStatusService statusService;

    public ImChannelPublicController(PlatformImChannelConfigService configService,
                                     ImChannelRuntimeStatusService statusService) {
        this.configService = configService;
        this.statusService = statusService;
    }

    @GetMapping("/group-config")
    public Result<GroupChatPublicConfigVO> groupConfig() {
        String provider = configService.selectedProvider();
        PlatformImChannelConfigDO config = configService.findGroupChat(provider);
        ImChannelRuntimeStatusService.RuntimeStatus status = statusService.status(provider);

        GroupChatPublicConfigVO vo = new GroupChatPublicConfigVO();
        vo.setProvider(provider);
        vo.setConfigured(config != null);
        vo.setDesiredEnabled(config != null && Integer.valueOf(1).equals(config.getEnabled()));
        vo.setReady(configService.isGroupChatReady(provider));
        vo.setAppKey(config == null ? null : config.getAppKey());
        vo.setRobotCode(config == null ? null : config.getRobotCode());
        vo.setSelectionGeneration(configService.selectionGeneration());
        vo.setStatus(status.status().name());
        vo.setDrainingCount(status.drainingCount());
        vo.setFailedCount(status.failedCount());
        vo.setUnknownCount(status.unknownCount());
        return Result.ok(vo);
    }
}
