import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'

class RuntimeTerraformInitTest(unittest.TestCase):
    def run_helper(self, root, fail=False, mode='remote', nested=False):
        manifest = root / 'manifest.json'
        backend = root / 'backend.hcl'
        backend.write_text('bucket="existing-state"\n')
        data = {'terraform': {'stateReference': str(backend), 'workspace': 'existing'}}
        if nested:
            data['terraform']['stateMode'] = mode
        else:
            data['stateMode'] = mode
        manifest.write_text(json.dumps(data))
        work = root / 'terraform'
        work.mkdir()
        binary = root / 'bin'
        binary.mkdir()
        fake = binary / 'terraform'
        fake.write_text('''#!/usr/bin/env python3
import json, os, pathlib, stat, sys
args=sys.argv[1:]
data=pathlib.Path(os.environ.get('TF_DATA_DIR', '/no-cache'))
row={'args':args,'data':str(data)}
if args[1]=='init':
    backend=pathlib.Path(next(x.split('=',1)[1] for x in args if x.startswith('-backend-config=')))
    row.update(dataMode=stat.S_IMODE(data.stat().st_mode),backendMode=stat.S_IMODE(backend.stat().st_mode),parentMode=stat.S_IMODE(data.parent.stat().st_mode))
    print('PRIVATE_BACKEND_SECRET')
    print('PRIVATE_BACKEND_SECRET',file=sys.stderr)
    if os.environ.get('FAIL_INIT')!='1': (data/'initialized').touch()
with open(os.environ['CALL_LOG'],'a') as f: f.write(json.dumps(row)+'\\n')
if args[1]=='init' and os.environ.get('FAIL_INIT')=='1': sys.exit(9)
if args[1]=='output' and os.environ.get('STATE_MODE')!='local' and not (data/'initialized').exists(): sys.exit(8)
''')
        fake.chmod(0o700)
        log = root / 'calls.jsonl'
        env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'], CALL_LOG=str(log), STATE_MODE=mode)
        if fail: env['FAIL_INIT'] = '1'
        result = subprocess.run(['bash', '-c', 'source "$1"; initialize_runtime_terraform "$2" "$3"; terraform -chdir="$3" output -json expected_tags', '_', str(SCRIPTS / 'lib.sh'), str(manifest), str(work)], text=True, capture_output=True, env=env)
        rows = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
        return result, rows, work

    def test_restored_remote_state_initializes_before_output_and_cleans_private_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            result, rows, work = self.run_helper(Path(directory))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual([row['args'][1] for row in rows], ['init', 'workspace', 'output'])
            self.assertEqual(rows[0]['dataMode'], 0o700)
            self.assertEqual(rows[0]['parentMode'], 0o700)
            self.assertEqual(rows[0]['backendMode'], 0o600)
            self.assertEqual(len({row['data'] for row in rows}), 1)
            self.assertFalse(Path(rows[0]['data']).parent.exists())
            self.assertFalse((work / '.terraform').exists())
            self.assertIn('-reconfigure', rows[0]['args'])
            self.assertIn('-input=false', rows[0]['args'])
            self.assertNotIn('-force-copy', rows[0]['args'])
            self.assertNotIn('-migrate-state', rows[0]['args'])
            self.assertNotIn('PRIVATE_BACKEND_SECRET', result.stdout + result.stderr)

    def test_init_failure_stops_output_and_cleans_without_printing_backend_secrets(self):
        with tempfile.TemporaryDirectory() as directory:
            result, rows, _ = self.run_helper(Path(directory), fail=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual([row['args'][1] for row in rows], ['init'])
            self.assertFalse(Path(rows[0]['data']).parent.exists())
            self.assertNotIn('PRIVATE_BACKEND_SECRET', result.stdout + result.stderr)

    def test_nested_only_oss_mode_initializes_backend(self):
        with tempfile.TemporaryDirectory() as directory:
            result, rows, _ = self.run_helper(Path(directory), mode='oss', nested=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual([row['args'][1] for row in rows], ['init', 'workspace', 'output'])
            self.assertFalse(Path(rows[0]['data']).parent.exists())

    def test_local_state_retains_existing_output_path(self):
        with tempfile.TemporaryDirectory() as directory:
            result, rows, _ = self.run_helper(Path(directory), mode='local')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual([row['args'][1] for row in rows], ['output'])

if __name__ == '__main__':
    unittest.main()
