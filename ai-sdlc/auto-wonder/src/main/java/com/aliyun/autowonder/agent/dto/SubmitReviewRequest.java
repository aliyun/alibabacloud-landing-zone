package com.aliyun.autowonder.agent.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SubmitReviewRequest {
    /** Optional submitter note; blank is normalized to null and must not exceed 500 characters. */
    private String note;
}
