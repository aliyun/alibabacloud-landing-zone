package com.aliyun.autowonder.workspace.dto;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Getter
@Setter
public class MemberVO {
    private Long userId;
    private String username;
    private String email;
    private String nickname;
    private Date joinedAt;
    private boolean owner;
    private WorkspaceAccessLevel accessLevel;
    private List<String> identityTags;
}
