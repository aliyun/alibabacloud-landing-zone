package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegistrationReviewRequest {
    /** APPROVE 或 REJECT。 */
    private String decision;
}
