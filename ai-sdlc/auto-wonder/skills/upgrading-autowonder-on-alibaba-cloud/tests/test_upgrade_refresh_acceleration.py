import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
spec=importlib.util.spec_from_file_location('upgrade_refresh_acceleration',SCRIPTS/'upgrade_info.py')
info=importlib.util.module_from_spec(spec);spec.loader.exec_module(info)

class RefreshAccelerationTests(unittest.TestCase):
    def refresh(self, explicit=None):
        configs=[]
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);(root/'terraform').mkdir()
            lock=root/'terraform/.terraform.lock.hcl';lock.write_text('original checksums')
            def command(argv,environment,output_file=None):
                config=Path(environment['TF_CLI_CONFIG_FILE'])
                configs.append((str(config),config.read_text(),config.stat().st_mode & 0o777))
                self.assertNotIn('-upgrade',argv)
                if output_file: output_file.write_text('{}')
            environment=dict(os.environ);environment.pop('TF_CLI_CONFIG_FILE',None)
            if explicit:
                custom=root/'explicit.tfrc';custom.write_text('custom provider config')
                environment['TF_CLI_CONFIG_FILE']=str(custom)
            with patch.dict(os.environ,environment,clear=True),patch.object(info,'run_command',side_effect=command),patch.object(info,'normalize_terraform_outputs',return_value={}):
                info.run_terraform_output(root,{'terraform':{'workingDirectory':'terraform','backendMode':'local'}})
                self.assertEqual(environment,dict(os.environ))
            self.assertEqual('original checksums',lock.read_text())
            if explicit: self.assertEqual(str(custom),configs[0][0])
        return configs

    def test_default_mirror_is_private_process_scoped_and_preserves_lock(self):
        configs=self.refresh()
        self.assertEqual(configs[0],configs[1])
        self.assertEqual(0o600,configs[0][2])
        self.assertFalse(Path(configs[0][0]).exists())
        self.assertIn('https://mirrors.aliyun.com/terraform/',configs[0][1])
        for provider in ('aliyun/alicloud','hashicorp/alicloud'):
            self.assertEqual(2,configs[0][1].count('registry.terraform.io/'+provider))

    def test_preserves_explicit_config(self):
        self.assertEqual('custom provider config',self.refresh(True)[0][1])

    def test_timeout_diagnostic_does_not_expose_stderr(self):
        result=subprocess.CompletedProcess([],1,stderr='authentication checksums request https://registry.terraform.io?secret=PRIVATE failed: Client.Timeout exceeded')
        with patch.object(info.subprocess,'run',return_value=result):
            with self.assertRaisesRegex(info.UpgradeInfoError,'network timeout') as raised:
                info.run_command(['terraform','init'],{})
        self.assertNotIn('PRIVATE',str(raised.exception))
        self.assertNotIn('https://',str(raised.exception))
