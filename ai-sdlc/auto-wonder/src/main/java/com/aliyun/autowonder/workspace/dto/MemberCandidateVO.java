package com.aliyun.autowonder.workspace.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MemberCandidateVO {
    private Long userId;
    private String username;
    private String email;
    private String nickname;
}
