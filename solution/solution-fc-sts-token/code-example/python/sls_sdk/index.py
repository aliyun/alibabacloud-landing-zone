from aliyun.log import LogClient

def handler(event, context):
    endpoint = 'cn-hangzhou.log.aliyuncs.com'

    # 从上下文获取临时凭证
    creds = context.credentials

    # 初始化 LogClient
    client = LogClient(endpoint, creds.access_key_id, creds.access_key_secret, creds.security_token)

    # 调用ListProject接口
    response = client.list_project()

    return response.get_projects()