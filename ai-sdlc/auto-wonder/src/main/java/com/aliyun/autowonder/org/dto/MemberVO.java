package com.aliyun.autowonder.org.dto;

import com.aliyun.autowonder.access.OrgAccessLevel;
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
    private OrgAccessLevel accessLevel;
    private List<String> identityTags;
}
