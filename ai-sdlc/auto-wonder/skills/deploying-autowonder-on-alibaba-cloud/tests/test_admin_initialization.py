"""Exercise the remote initializer with local API doubles; no cloud or application access."""
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/internal/admin-init.sh'

@unittest.skipUnless(all(shutil.which(x) for x in ('bash', 'jq', 'openssl')), 'native build tools required')
class AdminInitialization(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / 'bin'; self.bin.mkdir()
        for name, body in {
            'flock': 'exit 0\n',
            'stat': 'if [ "$2" = "%a" ]; then echo 600; else id -u; fi\n',
        }.items():
            p = self.bin / name; p.write_text('#!/bin/sh\n' + body); p.chmod(0o700)
        import sys
        p = self.bin / 'curl'
        p.write_text('#!' + sys.executable + '\n' + '''import json,os,pathlib,sys
r=pathlib.Path(os.environ['FIXTURE_ROOT']);a=sys.argv[1:];url=a[-1]
with (r/'calls').open('a') as f:f.write(url+'\\n')
out=pathlib.Path(a[a.index('--output')+1]) if '--output' in a else None
request=json.loads(pathlib.Path(a[a.index('--data-binary')+1][1:]).read_text())
if url.endswith('/register'):
 if (r/'account').exists():out.write_text('{}');print('409',end='')
 else:
  (r/'account').write_text(json.dumps(request))
  if os.environ.get('LOSE_RESPONSE')=='1':sys.exit(28)
  out.write_text('{"success":true}');print('200',end='')
elif url.endswith('/login'):
 saved=json.loads((r/'account').read_text())
 if saved['password']!=request['password']:sys.exit(22)
 print('{"success":true,"data":{"accessToken":"test-only"}}')
else:sys.exit(99)
''');p.chmod(0o700)
        self.key = self.root/'key.pem'; self.public=self.root/'public.pem'
        subprocess.run(['openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(self.key)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        subprocess.run(['openssl','pkey','-in',str(self.key),'-pubout','-out',str(self.public)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        self.env=dict(os.environ,PATH=str(self.bin)+os.pathsep+os.environ['PATH'],FIXTURE_ROOT=str(self.root),credential_file=str(self.root/'credentials.json'),deployment_b64=base64.b64encode(b'fixture-deployment').decode(),public_key_b64=base64.b64encode(self.public.read_bytes()).decode())
    def run_init(self, **env):
        return subprocess.run(['bash',str(SCRIPT)],env=dict(self.env,**env),capture_output=True,text=True)
    def test_first_creation_has_no_login_or_workspace_and_encrypted_handoff(self):
        result=self.run_init();self.assertEqual(result.returncode,0)
        self.assertEqual((self.root/'calls').read_text().splitlines(),['http://127.0.0.1:7001/api/auth/register'])
        saved=json.loads((self.root/'credentials.json').read_text())
        self.assertNotIn(saved['password'],result.stdout+result.stderr)
        cipher=next(x.split('=',1)[1] for x in result.stdout.splitlines() if x.startswith('HANDOFF_CIPHERTEXT='))
        clear=subprocess.run(['openssl','pkeyutl','-decrypt','-inkey',str(self.key),'-pkeyopt','rsa_padding_mode:oaep','-pkeyopt','rsa_oaep_md:sha256'],input=base64.b64decode(cipher),capture_output=True,check=True)
        self.assertEqual(json.loads(clear.stdout),{k:saved[k] for k in ('username','password')})
        self.assertEqual((self.root/'credentials.json').stat().st_mode & 0o777,0o600)
    def test_lost_response_reuses_password_and_preserves_account(self):
        self.assertNotEqual(self.run_init(LOSE_RESPONSE='1').returncode,0)
        account=(self.root/'account').read_bytes();saved=(self.root/'credentials.json').read_bytes()
        self.assertEqual(self.run_init().returncode,0)
        self.assertEqual((self.root/'account').read_bytes(),account)
        self.assertEqual((self.root/'credentials.json').read_bytes(),saved)
        self.assertTrue((self.root/'calls').read_text().endswith('/api/auth/login\n'))
    def test_existing_unknown_admin_is_never_replaced(self):
        account=b'{"username":"admin","password":"existing-user-password"}'
        (self.root/'account').write_bytes(account)
        self.assertNotEqual(self.run_init().returncode,0)
        self.assertEqual((self.root/'account').read_bytes(),account)
    def test_other_deployment_is_rejected_before_api(self):
        self.assertEqual(self.run_init().returncode,0)
        (self.root/'calls').unlink()
        self.assertNotEqual(self.run_init(deployment_b64=base64.b64encode(b'other').decode()).returncode,0)
        self.assertFalse((self.root/'calls').exists())
    def test_symlink_credentials_rejected(self):
        (self.root/'credentials.json').symlink_to(self.root/'unrelated')
        self.assertNotEqual(self.run_init().returncode,0)
        self.assertFalse((self.root/'calls').exists())
