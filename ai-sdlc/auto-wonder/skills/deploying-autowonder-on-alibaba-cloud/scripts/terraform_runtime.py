#!/usr/bin/env python3
"""Private, process-scoped Terraform provider routing for isolated phases."""
import argparse
import os
from pathlib import Path


CONFIG = '''
provider_installation {
  network_mirror {
    url = "https://mirrors.aliyun.com/terraform/"
    include = [
      "registry.terraform.io/aliyun/alicloud",
      "registry.terraform.io/hashicorp/alicloud",
    ]
  }
  direct {
    exclude = [
      "registry.terraform.io/aliyun/alicloud",
      "registry.terraform.io/hashicorp/alicloud",
    ]
  }
}
'''


def configure(environment, directory):
    environment = dict(environment)
    if not environment.get('TF_CLI_CONFIG_FILE'):
        path = Path(directory) / 'terraform-init-acceleration.tfrc'
        descriptor = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
            stream.write(CONFIG)
        environment['TF_CLI_CONFIG_FILE'] = str(path)
    return environment


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config-dir', required=True)
    args = parser.parse_args()
    print(configure(os.environ, args.config_dir)['TF_CLI_CONFIG_FILE'])
