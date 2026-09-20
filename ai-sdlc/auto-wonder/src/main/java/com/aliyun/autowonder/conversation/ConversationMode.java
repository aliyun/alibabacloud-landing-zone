package com.aliyun.autowonder.conversation;

/**
 * 渠道决定 Agent 在一轮对话里的行为边界。抽成枚举是为了让「哪个渠道追加哪段提示」
 * 有唯一出处：新增渠道时必须显式选一种模式，不会悄悄沿用别人的边界。
 */
public enum ConversationMode {

    /**
     * 平台管家对话。读取类操作直接做；任何会改动平台数据的操作都必须先产出参数冻结的
     * 行动计划，等会话 Owner 本人显式确认后才能执行，禁止边解释边动手。
     */
    PLATFORM_ASSISTANT(
            "\n\n当前是平台管家对话，与你交谈的就是这个会话的唯一 Owner。"
                    + "查询、读取、汇总类请求直接完成，不要反复确认。"
                    + "任何会创建、修改、删除平台数据的操作，必须先调用"
                    + " autowonder.propose_platform_actions 产出参数冻结的一次性行动计划，"
                    + "把目标、参数、影响面和执行步骤讲清楚，等 Owner 显式确认后再调用"
                    + " autowonder.execute_platform_action_plan；未确认前不得执行任何写操作，"
                    + "也不得自行放宽或替换计划里的参数。"
                    + "Owner 选中的文件读取失败时必须如实报告，禁止静默忽略后继续作答。"
                    + "只做工程可视化配图，不做通用艺术文生图。",
            false),

    /** 工单需求澄清。 */
    WORKITEM_CLARIFICATION(
            "\n\n当前是工单需求澄清会话。遵循身份配置完成澄清；仅在用户明确确认最终方案后上传产物。"
                    + "用户要求重写时，先清理本次澄清上传的旧产物，再上传新版。",
            false),

    /** 内部情报渠道：没有真人在线回答，交互工具的提问会掉进黑洞。 */
    PLATFORM_INTERNAL("", true),

    /** 其余渠道：能否交互取决于执行器是否声明了 ACP 能力。 */
    GENERIC("", false);

    private final String promptSuffix;
    private final boolean oneWay;

    ConversationMode(String promptSuffix, boolean oneWay) {
        this.promptSuffix = promptSuffix;
        this.oneWay = oneWay;
    }

    public static ConversationMode of(String channel) {
        if (channel == null) {
            return GENERIC;
        }
        if (PlatformConversationChannel.CHANNEL.equals(channel)) {
            return PLATFORM_ASSISTANT;
        }
        if ("WORKITEM_CLARIFICATION".equals(channel)) {
            return WORKITEM_CLARIFICATION;
        }
        if (PlatformIntelligenceChannelSink.CHANNEL.equals(channel)) {
            return PLATFORM_INTERNAL;
        }
        return GENERIC;
    }

    /** 渠道本身就注定单向，与执行器能力无关。 */
    public boolean oneWay() {
        return oneWay;
    }

    public String promptSuffix() {
        return promptSuffix;
    }
}
