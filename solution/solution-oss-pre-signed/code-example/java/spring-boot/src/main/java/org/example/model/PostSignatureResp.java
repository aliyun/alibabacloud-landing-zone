package org.example.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PostSignatureResp {

    private String accessKeyId;

    private String securityToken;

    private String policy;

    private String signature;

    private String dir;

    private String host;

    private String expires;

    private String callback;
}
