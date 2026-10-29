package com.aliyun.autowonder.im;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupInfoCommandTest {

    @Test
    void recognizesEveryGroupInfoSpelling() {
        assertTrue(GroupInfoCommand.isGroupInfoCommand("群信息"));
        assertTrue(GroupInfoCommand.isGroupInfoCommand(" 群信息 "));
        assertTrue(GroupInfoCommand.isGroupInfoCommand("groupinfo"));
        assertTrue(GroupInfoCommand.isGroupInfoCommand("/groupinfo"));
        assertFalse(GroupInfoCommand.isGroupInfoCommand("群信息 附加"));
        assertFalse(GroupInfoCommand.isGroupInfoCommand(null));
        assertFalse(GroupInfoCommand.isGroupInfoCommand("   "));
    }

    @Test
    void recognizesHistoricalBindingWordsAndPrefixesOnlyAsRemovedCommands() {
        for (String word : new String[]{"confirm", "/confirm", "确认", "bind", "/bind", "绑定",
                "/绑定"}) {
            assertTrue(GroupInfoCommand.isLegacyBindingCommand(word), word);
            assertTrue(GroupInfoCommand.isLegacyBindingCommand(word + " anything"), word);
            assertTrue(GroupInfoCommand.isLegacyBindingCommand(word + "\tanything"), word);
        }
        assertTrue(GroupInfoCommand.isLegacyBindingCommand("绑定群聊:whatever-code"));
        assertTrue(GroupInfoCommand.isLegacyBindingCommand("绑定群聊"));
        assertFalse(GroupInfoCommand.isLegacyBindingCommand("please confirm the deployment"));
        assertFalse(GroupInfoCommand.isLegacyBindingCommand("群信息"));
        assertFalse(GroupInfoCommand.isLegacyBindingCommand(null));
        assertFalse(GroupInfoCommand.isLegacyBindingCommand("  "));
    }
}
