package com.aliyun.autowonder.harness;

import java.util.List;

/** 模板中的一个 SDLC 流程；无 SDLC 角色保持 null，不生成流程。 */
public record HarnessSdlc(String name, String description, String workType, List<HarnessSdlcStep> steps) {
}
