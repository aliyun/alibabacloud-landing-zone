import sys
import json
import logging
import warnings

from alibaba_cloud_secretsmanager_client.secret_manager_cache_client_builder import SecretManagerCacheClientBuilder

warnings.filterwarnings("ignore")


def get_secret(secret_name):
    secret_manager_client_builder = SecretManagerCacheClientBuilder()

    client_logger = logging.Logger("secret_manager_client", level="ERROR")
    secret_cache_client = secret_manager_client_builder.with_logger(logger=client_logger).new_client()

    secret_info_resp = secret_cache_client.get_secret_info(secret_name)
    return json.loads(secret_info_resp.__dict__['secret_value'])


def get_all_secret(ali_secret_name, az_secret_name):
    alicloud_secret = get_secret(ali_secret_name)
    alicloud_secret['access_key'] = alicloud_secret['AccessKeyId']
    alicloud_secret['secret_key'] = alicloud_secret['AccessKeySecret']
    del alicloud_secret['AccessKeyId']
    del alicloud_secret['AccessKeySecret']
    azure_secret = get_secret(az_secret_name)
    all_secret = dict(alicloud_secret, **azure_secret)
    return all_secret


def write_secret_env_2_file(secret, path):
    export_str = '\nexport ALICLOUD_ACCESS_KEY=' + secret['access_key'] + \
                 '\nexport ALICLOUD_SECRET_KEY=' + secret['secret_key'] + \
                 '\nexport ARM_SUBSCRIPTION_ID=' + secret['arm_subscription_id'] + \
                 '\nexport ARM_TENANT_ID=' + secret['arm_tenant_id'] + \
                 '\nexport ARM_CLIENT_ID=' + secret['arm_client_id'] + \
                 '\nexport ARM_CLIENT_SECRET=' + secret['arm_client_secret']

    with open(path, 'a+') as f:
        f.write(export_str)


# python kms_secret.py acs/ram/user/admin_poc terraform-ak-azure
if __name__ == '__main__':
    alicloud_secret_name = sys.argv[1]
    azure_secret_name = sys.argv[2]
    env_file_path = '.bashrc'
    if len(sys.argv) >= 4:
        env_file_path = sys.argv[3]

    secret_dict = get_all_secret(alicloud_secret_name, azure_secret_name)

    write_secret_env_2_file(secret_dict, env_file_path)
