package com.aliyun.autowonder.conversation;

/**
 * 会话协议能力名。服务端按这些名字探测执行器，Runtime 按同样的名字声明，
 * 两边必须逐字一致，所以只在这里出现一次。
 */
public final class ConversationProtocolFeatures {
    public static final String TURN_EVENT = "CONVERSATION_TURN_EVENT";
    public static final String TURN_CANCEL = "CONVERSATION_TURN_CANCEL";
    public static final String ACP_INTERACTION = "CONVERSATION_ACP_INTERACTION_V1";
    /** Runtime 能在一次下发里接收 Owner 选中的文件清单。 */
    public static final String ATTACHMENT_MANIFEST_V1 = "ATTACHMENT_MANIFEST_V1";
    /** Runtime 能把生成的文件作为产物回传，供在线预览和下载。 */
    public static final String ARTIFACT_OUTPUT_V1 = "ARTIFACT_OUTPUT_V1";
    /** Runtime 能承接参数冻结的行动计划，并在执行前等待 Owner 确认。 */
    public static final String ACTION_PLAN_V1 = "ACTION_PLAN_V1";

    private ConversationProtocolFeatures() {
    }
}
