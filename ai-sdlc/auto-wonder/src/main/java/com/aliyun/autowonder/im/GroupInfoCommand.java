package com.aliyun.autowonder.im;

import java.util.Locale;
import java.util.Set;

/**
 * Stateless recognition of platform group-chat management commands. Group confirmation was
 * removed, so a historical binding word or a {@code 绑定群聊} message only produces guidance and
 * never binds anything.
 */
public final class GroupInfoCommand {
    /** The literal protocol prefix; it is never translated. */
    public static final String LEGACY_BIND_PREFIX = "绑定群聊";
    private static final Set<String> GROUP_INFO_COMMANDS =
            Set.of("groupinfo", "/groupinfo", "群信息");
    /** Historical binding commands, recognized only to reply that confirmation was removed. */
    private static final Set<String> LEGACY_BIND_COMMANDS =
            Set.of("confirm", "/confirm", "确认", "bind", "/bind", "绑定", "/绑定");

    private GroupInfoCommand() {
    }

    public static boolean isGroupInfoCommand(String text) {
        return text != null && GROUP_INFO_COMMANDS.contains(text.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isLegacyBindingCommand(String text) {
        if (text == null) {
            return false;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String legacy : LEGACY_BIND_COMMANDS) {
            if (lower.equals(legacy) || lower.startsWith(legacy + " ")
                    || lower.startsWith(legacy + "\t")) {
                return true;
            }
        }
        return value.startsWith(LEGACY_BIND_PREFIX);
    }
}
