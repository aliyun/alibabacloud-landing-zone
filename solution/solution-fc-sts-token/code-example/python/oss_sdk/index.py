import json
import oss2
import os

def handler(event, context):
    endpoint = 'http://oss-cn-hangzhou.aliyuncs.com'
    bucket = 'web****'
    object = 'myObj'
    message = 'test-message'
    
    # 从系统预留环境变量获取凭证信息，构造OSS SDK的凭证
    auth = oss2.StsAuth(
        os.environ.get('ALIBABA_CLOUD_ACCESS_KEY_ID'),
        os.environ.get('ALIBABA_CLOUD_ACCESS_KEY_SECRET'),
        os.environ.get('ALIBABA_CLOUD_SECURITY_TOKEN')
    )

    # 调用OpenAPI
    bucket = oss2.Bucket(auth, endpoint, bucket)
    bucket.put_object(object, message)

    return 'success'