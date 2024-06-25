import json
import oss2

def handler(event, context):
    endpoint = 'http://oss-cn-hangzhou.aliyuncs.com'
    bucket = 'web****'
    object = 'myObj'
    message = 'test-message'

    # 从上下文获取临时凭证
    creds = context.credentials

    # 转化为OSS SDK的凭证
    auth = oss2.StsAuth(creds.access_key_id, creds.access_key_secret, creds.security_token)

    # 调用OpenAPI
    bucket = oss2.Bucket(auth, endpoint, bucket)
    bucket.put_object(object, message)

    return 'success'