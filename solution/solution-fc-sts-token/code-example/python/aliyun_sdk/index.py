from aliyunsdksts.request.v20150401.GetCallerIdentityRequest import GetCallerIdentityRequest
from aliyunsdkcore.client import AcsClient
from aliyunsdkcore.auth.credentials import StsTokenCredential

def handler(event, context):
    # 从上下文获取凭证信息
    creds = context.credentials

    # 创建凭证对象
    credentials = StsTokenCredential(creds.access_key_id, creds.access_key_secret, creds.security_token)

    # 初始化客户端，设置地区等信息
    client = AcsClient(region_id='cn-hangzhou', credential=credentials)

    # 创建请求对象
    request = GetCallerIdentityRequest()

    # 设置参数，例如可以设置过滤条件等，这里只展示最基本的调用
    request.set_accept_format('json')

    # 发起请求并获取响应
    response = client.do_action_with_exception(request)

    # 打印响应结果
    return(str(response, encoding='utf-8'))