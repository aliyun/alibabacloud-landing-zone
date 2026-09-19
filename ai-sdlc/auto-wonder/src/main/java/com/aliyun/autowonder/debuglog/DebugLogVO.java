package com.aliyun.autowonder.debuglog;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class DebugLogVO {
    private Long id;
    private String sourceType;
    private Long sourceId;
    private Long dispatchId;
    private Long agentId;
    private Integer runNo;
    private String dispatchStatus;
    private String objectKey;
    private Long sizeBytes;
    private String sha256;
    private Boolean truncated;
    private String uploadChannel;
    private String status;
    private String errorMessage;
    private Date gmtCreate;
    /** 仅 UPLOADED 行非空：presignGet 出的临时下载 URL（TTL 10 分钟）。 */
    private String downloadUrl;

    public static DebugLogVO from(DebugLogDO row, String downloadUrl) {
        DebugLogVO vo = new DebugLogVO();
        vo.setId(row.getId());
        vo.setSourceType(row.getSourceType());
        vo.setSourceId(row.getSourceId());
        vo.setDispatchId(row.getDispatchId());
        vo.setAgentId(row.getAgentId());
        vo.setRunNo(row.getRunNo());
        vo.setDispatchStatus(row.getDispatchStatus());
        vo.setObjectKey(row.getObjectKey());
        vo.setSizeBytes(row.getSizeBytes());
        vo.setSha256(row.getSha256());
        vo.setTruncated(row.getTruncated());
        vo.setUploadChannel(row.getUploadChannel());
        vo.setStatus(row.getStatus());
        vo.setErrorMessage(row.getErrorMessage());
        vo.setGmtCreate(row.getGmtCreate());
        vo.setDownloadUrl(downloadUrl);
        return vo;
    }
}
