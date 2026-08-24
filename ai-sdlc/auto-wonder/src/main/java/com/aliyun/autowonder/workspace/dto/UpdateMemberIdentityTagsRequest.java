package com.aliyun.autowonder.workspace.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class UpdateMemberIdentityTagsRequest {
    private List<String> identityTags;
}
