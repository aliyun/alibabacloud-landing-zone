package com.aliyun.autowonder.squad.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 复制前置检查结果：eligible=false 时 missingOnlineVersions 列出没有在线版本的数字员工，
 * missingDependencies 列出悬空的成员/能力/流程引用（如 agent#42、skill#7）。
 */
@Getter
@Setter
public class CopyCheckResultVO {

    private boolean eligible = true;
    private List<MissingOnlineVersionVO> missingOnlineVersions = new ArrayList<>();
    private List<String> missingDependencies = new ArrayList<>();

    @Getter
    @Setter
    public static class MissingOnlineVersionVO {
        private Long agentId;
        private String agentName;

        public MissingOnlineVersionVO() {
        }

        public MissingOnlineVersionVO(Long agentId, String agentName) {
            this.agentId = agentId;
            this.agentName = agentName;
        }
    }
}
