package com.aliyun.autowonder.conversation.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

@Data
@Builder
public class PlatformConversationVO {
    private Long id;
    /** 不可变更的会话归属人，前端据此决定是否显示写操作入口。 */
    private Long ownerUserId;
    /** 当前调用者是否为 Owner；被分享人这里是 false，只能读。 */
    private boolean owner;
    private Long agentId;
    private String agentName;
    private String channelConversationId;
    private String title;
    /** AUTO 表示服务端按首条消息生成，USER 表示用户改过名、不再被自动覆盖。 */
    private String titleSource;
    private String status;
    private boolean executorOnline;
    private boolean streamingSupported;
    private boolean cancelSupported;
    private boolean acpInteractionSupported;
    /** Runtime 能否接收 Owner 选中的文件清单。 */
    private boolean attachmentManifestSupported;
    /** Runtime 能否回传生成的文件供预览下载。 */
    private boolean artifactOutputSupported;
    /** Runtime 能否承接参数冻结的行动计划；为 false 时前端必须隐藏所有写操作。 */
    private boolean actionPlanSupported;
    private String cliSessionRef;
    private String processingStatus;
    private Long processingTurnId;
    private Date archivedAt;
    private Date lastTurnAt;
    private Date gmtCreate;
    /** 仅详情接口填充。 */
    private List<PlatformTurnVO> turns;
    /** 仅详情接口且仅 Owner 可见：分享名单本身就是隐私。 */
    private List<PlatformShareVO> shares;
    /** 仅详情接口填充：刷新页面后据此恢复未解决的问答卡片。 */
    private List<ClarificationElicitationVO> pendingElicitations;
    /** 仅详情接口填充：斜杠命令快照。 */
    private List<ClarificationSlashCommandVO> availableCommands;
}
