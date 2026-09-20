import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SKILLS = Path(__file__).resolve().parents[2]


class RuntimeTerraformAccelerationTests(unittest.TestCase):
    def invoke(self, skill, explicit=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            backend = root / 'backend.hcl'
            backend.write_text('bucket="fixture"\n')
            manifest = root / 'manifest.json'
            manifest.write_text(json.dumps({'stateMode': 'oss', 'terraform': {
                'stateReference': str(backend), 'workspace': 'default'}}))
            work = root / 'terraform'
            work.mkdir()
            lock = work / '.terraform.lock.hcl'
            lock.write_bytes(b'original checksums\n')
            binary = root / 'bin'
            binary.mkdir()
            terraform = binary / 'terraform'
            terraform.write_text('#!' + sys.executable + '\n' + '''import json,os,sys
from pathlib import Path
p=os.environ.get('TF_CLI_CONFIG_FILE')
config=Path(p) if p else None
Path(os.environ['PROBE_OUTPUT']).write_text(json.dumps({
 'path':p,'config':config.read_text() if config else '',
 'mode':config.stat().st_mode & 0o777 if config else None,'args':sys.argv[1:]}))
''')
            terraform.chmod(0o700)
            env = dict(os.environ, PATH=str(binary)+os.pathsep+os.environ['PATH'],
                       PROBE_OUTPUT=str(root/'result.json'), TMPDIR=str(root),
                       AUTOWONDER_PYTHON=sys.executable)
            env.pop('TF_CLI_CONFIG_FILE', None)
            if explicit:
                config = root / 'operator-config.tfrc'
                config.write_text('operator provider configuration\n')
                env['TF_CLI_CONFIG_FILE'] = str(config)
            result = subprocess.run(['bash', '-c',
                'source "$1"; initialize_runtime_terraform "$2" "$3"',
                'test', str(SKILLS/skill/'scripts/lib.sh'), str(manifest), str(work)],
                env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            report = json.loads((root/'result.json').read_text())
            self.assertEqual(b'original checksums\n', lock.read_bytes())
            self.assertNotIn('-upgrade', report['args'])
            if explicit:
                self.assertEqual(str(config), report['path'])
                self.assertEqual('operator provider configuration\n', config.read_text())
            else:
                self.assertIn('https://mirrors.aliyun.com/terraform/', report['config'])
                self.assertEqual(0o600, report['mode'])
                self.assertFalse(Path(report['path']).exists(), 'temporary config must be cleaned')

    def test_independent_runtime_phase_uses_private_mirror(self):
        for skill in ('deploying-autowonder-on-alibaba-cloud', 'upgrading-autowonder-on-alibaba-cloud'):
            with self.subTest(skill=skill):
                self.invoke(skill)

    def test_operator_configuration_is_preserved(self):
        for skill in ('deploying-autowonder-on-alibaba-cloud', 'upgrading-autowonder-on-alibaba-cloud'):
            with self.subTest(skill=skill):
                self.invoke(skill, explicit=True)
