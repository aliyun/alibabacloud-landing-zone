package com.aliyun.autowonder.auth.federation;

import java.util.Map;

/**
 * 协议适配器：把一条连接的协议细节封在注册表之外，只输出统一的外部身份。
 * 校验不通过时抛出带 35xxx 错误码的 BizException。
 */
public interface FederationProtocolAdapter {
    FederationProtocol protocol();

    AuthorizationRedirect buildAuthorizationRedirect(FederationConnectionDO connection,
                                                      String callbackUrl, String state);

    ExternalIdentity verifyCallback(FederationConnectionDO connection,
                                    FederationCallbackContext context,
                                    Map<String, String> stateAttributes);
}
