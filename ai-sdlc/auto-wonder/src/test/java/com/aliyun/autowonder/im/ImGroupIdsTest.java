package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImGroupIdsTest {

    @Test
    void normalizesDingTalkGroupIdsCaseSensitively() {
        assertEquals("cidAbc+/=:$@-.,", ImGroupIds.normalize("DINGTALK", " cidAbc+/=:$@-., "));
        assertEquals("cid_123", ImGroupIds.normalize("dingtalk", "cid_123"));
    }

    @Test
    void rejectsInvalidDingTalkGroupIds() {
        assertInvalid("DINGTALK", null);
        assertInvalid("DINGTALK", "   ");
        assertInvalid("DINGTALK", "cid with space");
        assertInvalid("DINGTALK", "cid#fragment");
        assertInvalid("DINGTALK", "a".repeat(257));
    }

    @Test
    void normalizesFeishuGroupIds() {
        assertEquals("oc_abc123XYZ", ImGroupIds.normalize("FEISHU", " oc_abc123XYZ\n"));
    }

    @Test
    void rejectsInvalidFeishuGroupIds() {
        assertInvalid("FEISHU", null);
        assertInvalid("FEISHU", "oc_");
        assertInvalid("FEISHU", "oc_abc-def");
        assertInvalid("FEISHU", "cli_abc");
        assertInvalid("FEISHU", "oc_" + "a".repeat(101));
    }

    @Test
    void unknownProviderOnlyRequiresNonBlankBoundedValue() {
        assertEquals("group-1", ImGroupIds.normalize("OTHER", " group-1 "));
        assertInvalid("OTHER", " ");
    }

    private static void assertInvalid(String provider, String groupId) {
        BizException error = assertThrows(BizException.class,
                () -> ImGroupIds.normalize(provider, groupId));
        assertEquals("10001", error.getCode());
        assertEquals("integration.group_id_invalid", error.getMessageKey());
    }
}
