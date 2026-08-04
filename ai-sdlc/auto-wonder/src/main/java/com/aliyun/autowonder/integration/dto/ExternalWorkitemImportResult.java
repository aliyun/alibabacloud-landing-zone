package com.aliyun.autowonder.integration.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExternalWorkitemImportResult {
    private Long workitemId;
    private Long importRecordId;
    private String sourceSystem;
    private String externalWorkitemId;
    private boolean created;
    private boolean updated;
    private boolean duplicate;
}
