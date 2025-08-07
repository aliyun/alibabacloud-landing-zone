import os
from aliyunsdksts.request.v20150401.GetCallerIdentityRequest import GetCallerIdentityRequest
from aliyunsdkcore.client import AcsClient
from aliyunsdkcore.auth.credentials import StsTokenCredential

def handler(event, context):
    
    # 从系统预留环境变量获取凭证信息，创建凭证对象
    credentials = StsTokenCredential(
        os.environ.get('ALIBABA_CLOUD_ACCESS_KEY_ID'),
        os.environ.get('ALIBABA_CLOUD_ACCESS_KEY_SECRET'),
        os.environ.get('ALIBABA_CLOUD_SECURITY_TOKEN')
    )

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