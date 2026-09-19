package com.aliyun.autowonder.sdlc.dto;

import lombok.Getter;
import lombok.Setter;

/** Row of the {@code GROUP BY sdlc_id} step-count aggregate that fills {@link SdlcVO#getStepCount()}. */
@Getter
@Setter
public class SdlcStepCount {
    private Long sdlcId;
    private Integer cnt;
}
