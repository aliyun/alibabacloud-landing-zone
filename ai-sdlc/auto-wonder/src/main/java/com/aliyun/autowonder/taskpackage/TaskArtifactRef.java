package com.aliyun.autowonder.taskpackage;

import lombok.Getter;
import lombok.Setter;

/** Reference to a teammate's artifact; bytes are fetched from ObjectStorage by ossRef. */
@Getter
@Setter
public class TaskArtifactRef {
    private String name;
    private String ossRef;
    /** Optional immutable content digest captured by a scheduled Run snapshot. */
    private String expectedSha256;
}
