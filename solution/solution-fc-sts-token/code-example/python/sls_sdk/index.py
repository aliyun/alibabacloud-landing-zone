import os
from aliyun.log import LogClient

def handler(event, context):
    endpoint = 'cn-hangzhou.log.aliyuncs.com'

    # 从系统预留环境变量获取凭证信息，初始化 LogClient
    client = LogClient(
        endpoint,
        os.environ.get('ALIBABA_CLOUD_ACCESS_KEY_ID'),
        os.environ.get('ALIBABA_CLOUD_ACCESS_KEY_SECRET'),
        os.environ.get('ALIBABA_CLOUD_SECURITY_TOKEN')
    )

    # 调用ListProject接口
    response = client.list_project()

    return response.get_projects()