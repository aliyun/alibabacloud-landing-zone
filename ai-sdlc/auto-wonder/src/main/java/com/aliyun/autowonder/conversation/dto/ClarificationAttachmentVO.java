package com.aliyun.autowonder.conversation.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class ClarificationAttachmentVO {
    private Long id;
    private String name;
    private String contentType;
    private Long size;
    /** 服务端按魔数确认可安全内联预览的位图（PNG/JPEG/GIF/WEBP/BMP）。 */
    private boolean previewable;
    private Date gmtCreate;
}
