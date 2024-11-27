# -*- coding: utf-8 -*-
import logging
import json
import os
import sys

from typing import List

from alibabacloud_eiam20211201.client import Client as Eiam20211201Client
from alibabacloud_eiam20211201 import models as eiam_20211201_models
from alibabacloud_tea_util import models as util_models
from alibabacloud_credentials.client import Client as CredClient
from alibabacloud_tea_openapi.models import Config

def handler(event, context):
    creds = context.credentials
    config = Config(access_key_id=creds.access_key_id,access_key_secret=creds.access_key_secret,security_token=creds.security_token)
    config.endpoint = os.environ['IDAAS_EIAM_ENDPOINT']
    client = Eiam20211201Client(config)

    run_synchronization_job_request = eiam_20211201_models.RunSynchronizationJobRequest(
            instance_id=os.environ['INSTANCE_ID'],
            target_id=os.environ['TARGET_ID'],
            target_type=os.environ['TARGET_TYPE']
        )
    runtime = util_models.RuntimeOptions()
    response = client.run_synchronization_job_with_options(run_synchronization_job_request, runtime)

    return(str(response.to_map()))