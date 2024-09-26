package org.example.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OssPostCallback {

    String callbackUrl;

    String callbackBody;

    String callbackBodyType;
}
