package com.aliyun.autowonder.conversation;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渠道模式的映射与提示词拼接。
 *
 * <p>这批断言的主要目的不是描述新行为，而是钉住重构前的行为：原来 resolveIdentity 里
 * 两段独立的 if 被抽成枚举后，除平台管家渠道外任何渠道的拼接结果都不允许变化。
 */
class ConversationModeTest {

    private static final String PLATFORM_CHANNEL = PlatformConversationChannel.CHANNEL;
    private static final String INTERNAL_CHANNEL = PlatformIntelligenceChannelSink.CHANNEL;
    private static final String CLARIFICATION_CHANNEL = "WORKITEM_CLARIFICATION";

    /** 与重构前 AgentConversationService 里的常量逐字一致。 */
    private static final String API_MODE_SUFFIX =
            "\n\n重要:你在API模式下运行,不能使用AskUserQuestion等交互工具。直接用文字提问和回复。";
    private static final String LEGACY_CLARIFICATION_SUFFIX =
            "\n\n当前是工单需求澄清会话。遵循身份配置完成澄清；仅在用户明确确认最终方案后上传产物。"
                    + "用户要求重写时，先清理本次澄清上传的旧产物，再上传新版。";

    private static final String IDENTITY = "IDENTITY";

    @Test
    void eachKnownChannelMapsToItsOwnMode() {
        assertEquals(ConversationMode.PLATFORM_ASSISTANT, ConversationMode.of(PLATFORM_CHANNEL));
        assertEquals(ConversationMode.WORKITEM_CLARIFICATION, ConversationMode.of(CLARIFICATION_CHANNEL));
        assertEquals(ConversationMode.PLATFORM_INTERNAL, ConversationMode.of(INTERNAL_CHANNEL));
    }

    @Test
    void missingAndUnknownChannelsFallBackToGeneric() {
        assertEquals(ConversationMode.GENERIC, ConversationMode.of(null));
        assertEquals(ConversationMode.GENERIC, ConversationMode.of(""));
        assertEquals(ConversationMode.GENERIC, ConversationMode.of("DINGTALK"));
        // 渠道串是大小写敏感的枚举名，写错必须退回 GENERIC 而不是猜一个模式。
        assertEquals(ConversationMode.GENERIC, ConversationMode.of("platform_assistant"));
    }

    @Test
    void onlyTheInternalIntelligenceChannelIsInherentlyOneWay() {
        assertTrue(ConversationMode.PLATFORM_INTERNAL.oneWay(),
                "内部情报渠道没有真人在线回答，交互工具的提问会掉进黑洞");
        assertFalse(ConversationMode.PLATFORM_ASSISTANT.oneWay());
        assertFalse(ConversationMode.WORKITEM_CLARIFICATION.oneWay());
        assertFalse(ConversationMode.GENERIC.oneWay());
    }

    @Test
    void clarificationKeepsThePromptItHadBeforeTheEnumExtraction() {
        assertEquals(LEGACY_CLARIFICATION_SUFFIX, ConversationMode.WORKITEM_CLARIFICATION.promptSuffix());
    }

    @Test
    void modesWithoutExtraGuidanceCarryAnEmptySuffix() {
        assertEquals("", ConversationMode.PLATFORM_INTERNAL.promptSuffix());
        assertEquals("", ConversationMode.GENERIC.promptSuffix());
    }

    @Test
    void preExistingChannelsComposeExactlyThePromptTheyHadBefore() {
        List<String> channels = Arrays.asList(CLARIFICATION_CHANNEL, INTERNAL_CHANNEL, "DINGTALK", null);
        for (String channel : channels) {
            for (boolean acpSupported : new boolean[] {true, false}) {
                assertEquals(legacyCompose(channel, acpSupported), currentCompose(channel, acpSupported),
                        "channel=" + channel + " acpSupported=" + acpSupported);
            }
        }
    }

    @Test
    void thePlatformChannelOnlyAddsItsOwnSuffixOnTopOfTheUnchangedBase() {
        for (boolean acpSupported : new boolean[] {true, false}) {
            assertEquals(legacyCompose(PLATFORM_CHANNEL, acpSupported)
                            + ConversationMode.PLATFORM_ASSISTANT.promptSuffix(),
                    currentCompose(PLATFORM_CHANNEL, acpSupported),
                    "acpSupported=" + acpSupported);
        }
    }

    @Test
    void thePlatformPromptNamesBothActionPlanToolsAndTheOwnerConfirmationGate() {
        String suffix = ConversationMode.PLATFORM_ASSISTANT.promptSuffix();
        assertTrue(suffix.contains("autowonder.propose_platform_actions"),
                "提示词必须点名产出行动计划的工具，否则模型会直接调通用写工具");
        assertTrue(suffix.contains("autowonder.execute_platform_action_plan"),
                "提示词必须点名执行行动计划的工具");
        assertTrue(suffix.contains("Owner"), "写操作只能由会话 Owner 本人确认");
    }

    @Test
    void thePlatformPromptForbidsSilentlySkippingAFailedFileRead() {
        String suffix = ConversationMode.PLATFORM_ASSISTANT.promptSuffix();
        assertTrue(suffix.contains("禁止静默忽略"), "选中文件读取失败必须如实上报");
    }

    @Test
    void thePlatformPromptLimitsImageGenerationToEngineeringVisuals() {
        String suffix = ConversationMode.PLATFORM_ASSISTANT.promptSuffix();
        assertTrue(suffix.contains("不做通用艺术文生图"));
    }

    /** 复刻重构前 resolveIdentity 的两段 if，作为不变的行为基线。 */
    private static String legacyCompose(String channel, boolean acpSupported) {
        StringBuilder sb = new StringBuilder(IDENTITY);
        if (INTERNAL_CHANNEL.equals(channel) || !acpSupported) {
            sb.append(API_MODE_SUFFIX);
        }
        if (CLARIFICATION_CHANNEL.equals(channel)) {
            sb.append(LEGACY_CLARIFICATION_SUFFIX);
        }
        return sb.toString();
    }

    /** 复刻重构后 resolveIdentity 的写法。 */
    private static String currentCompose(String channel, boolean acpSupported) {
        StringBuilder sb = new StringBuilder(IDENTITY);
        ConversationMode mode = ConversationMode.of(channel);
        if (mode.oneWay() || !acpSupported) {
            sb.append(API_MODE_SUFFIX);
        }
        sb.append(mode.promptSuffix());
        return sb.toString();
    }
}
