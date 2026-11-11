package com.aliyun.autowonder.harness;

import java.util.Map;

/** 模板附带的 Skill 包；files 为包内相对路径到原文内容的映射。 */
public record HarnessSkill(String key, String name, String type, String description, Map<String, String> files) {
}
