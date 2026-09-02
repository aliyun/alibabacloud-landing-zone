package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class UpdateTagsRequest {
    /**
     * New tag list; null or empty clears all tags.
     */
    private List<String> tags;
}
