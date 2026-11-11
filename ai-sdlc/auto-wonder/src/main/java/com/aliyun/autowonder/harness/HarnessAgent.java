package com.aliyun.autowonder.harness;

import java.util.List;

/** 模板中的数字人；sdlc 为 null 表示该角色不需要 SDLC。 */
public record HarnessAgent(String roleCode, String name, String roleName, String businessBackground,
        String responsibilities, String evolutionMode, List<String> skillRefs, HarnessSdlc sdlc) {
}
