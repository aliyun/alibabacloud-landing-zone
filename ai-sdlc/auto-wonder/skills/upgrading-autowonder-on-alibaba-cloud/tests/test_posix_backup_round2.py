"""Exercise real POSIX backup payloads; only cloud transport/host ownership are stubbed."""
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

import test_split_contract as fixtures

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'


class PosixBackupRetryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.env = {**os.environ, 'PATH': str(self.bin) + os.pathsep + os.environ['PATH'],
                    'COPYFILE_DISABLE': '1', 'BACKUP_TEST_ROOT': str(self.root), 'BACKUP_TEST_PYTHON': sys.executable}
        fixture = fixtures.UpgradeSkillSplitContractTests()
        self.manifest = fixture.write_target_manifest(self.root / 'manifest.json')
        data = json.loads(self.manifest.read_text())
        data.update(mode='upgrade', repositoryCommit='b' * 40,
                    deployment={'activeCommit': 'a' * 40}, upgradeInventory={'status': 'verified'},
                    upgrade={'fromCommit': 'a' * 40, 'toCommit': 'b' * 40, 'blockedReasons': [],
                             'confirmationRequired': False})
        self.manifest.write_text(json.dumps(data))
        for node in ('i-a', 'i-b'):
            host = self.root / node
            release = host / 'opt/releases' / ('a' * 12)
            release.mkdir(parents=True)
            (host / 'opt/current').symlink_to(release)
            (release / 'auto-wonder.jar').write_bytes(b'original jar')
            (release / 'autowonder-migrations.tar.gz').write_bytes(b'original migrations')
            (host / 'etc').mkdir()
            (host / 'unit').mkdir()
            (host / 'etc/autowonder.env').write_text('ORIGINAL=value\n')
            (host / 'unit/autowonder.service').write_text('original unit\n')
        binary = fixture.write_fake_aliyun(self.bin)
        binary.write_text(binary.read_text().replace('  *) exit 9 ;;', '''  *" ecs RunCommand "*|*" ecs DescribeInvocationResults "*)
    "$BACKUP_TEST_PYTHON" "$BACKUP_TEST_ROOT/remote.py" "$@" ;;
  *) exit 9 ;;'''))
        (self.root / 'remote.py').write_text('''import base64,json,os,re,subprocess,sys
from pathlib import Path
root=Path(os.environ['BACKUP_TEST_ROOT']);args=sys.argv[1:]
if 'RunCommand' in args:
    node=args[args.index('--InstanceId.1')+1];host=root/node
    command=args[args.index('--CommandContent')+1]
    script=base64.b64decode(re.search(r"printf '%s' '([^']+)'",command).group(1)).decode()
    for old,new in [('/opt/autowonder',host/'opt'),('/etc/autowonder',host/'etc'),('/etc/systemd/system',host/'unit')]:
        script=script.replace(old,str(new))
    result=subprocess.run(['bash','-c',script],capture_output=True)
    (root/'remote.stderr').write_bytes(result.stderr)
    with (root/'calls').open('a') as f: f.write(node+'\\n')
    (root/'result.json').write_text(json.dumps({'ExitCode':result.returncode,'Output':base64.b64encode(result.stdout).decode(),'InvocationStatus':'Finished'}))
    print(json.dumps({'InvokeId':'backup-test'}))
else:
    print(json.dumps({'InvocationResults':{'InvocationResult':[json.loads((root/'result.json').read_text())]}}))
''')
        # Owner/group names are Linux-specific; preserve all file operations and modes.
        self.executable('install', '''#!/usr/bin/env python3
import subprocess,sys
args=sys.argv[1:];filtered=[]
while args:
    arg=args.pop(0)
    if arg in ('-o','-g'): args.pop(0)
    else: filtered.append(arg)
sys.exit(subprocess.call(['/usr/bin/install']+filtered))
''')
        self.executable('sleep', '#!/bin/sh\nexit 0\n')
        self.executable('systemctl', '#!/bin/sh\nexit 0\n')
        self.executable('curl', '#!/bin/sh\nprintf success\n')
        self.executable('mv', '''#!/usr/bin/env python3
import os,subprocess,sys
args=sys.argv[1:]
if args[0]=='-Tf': os.replace(args[1],args[2])
else: sys.exit(subprocess.call(['/bin/mv']+args))
''')
        result = self.run_script('verify-deployment-targets.sh')
        self.assertEqual(0, result.returncode, result.stderr)
        self.approve()

    def executable(self, name, text):
        path = self.bin / name
        path.write_text(text)
        path.chmod(0o755)

    def run_script(self, name, *args):
        return subprocess.run(['bash', str(SCRIPTS / name), *args, '--manifest', str(self.manifest)],
                              env=self.env, text=True, capture_output=True, timeout=30)

    def approve(self):
        fingerprint = subprocess.check_output([sys.executable, '-B', str(SCRIPTS / 'upgrade_plan.py'),
                                              'fingerprint', '--manifest', str(self.manifest)], text=True).strip()
        data = json.loads(self.manifest.read_text())
        data['upgrade']['planFingerprint'] = fingerprint
        data['upgrade']['approval'] = {'status': 'approved', 'planFingerprint': fingerprint}
        self.manifest.write_text(json.dumps(data))

    def backup(self):
        result = self.run_script('upgrade-operations.sh', 'upgrade-backup')
        self.assertEqual(0, result.returncode, result.stderr +
                         (self.root / 'remote.stderr').read_text())
        return json.loads(self.manifest.read_text())

    def test_retry_preserves_original_backup_after_staging_changes_environment(self):
        self.backup()
        archive = self.root / 'i-a/opt/upgrade-rollback-backup.tar.gz'
        checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
        (self.root / 'i-a/etc/autowonder.env').write_text('TARGET=value\n')
        self.backup()
        self.assertEqual(checksum, hashlib.sha256(archive.read_bytes()).hexdigest(),
                         'retry replaced the original rollback backup')
        with tarfile.open(archive) as backup:
            self.assertEqual(b'ORIGINAL=value\n', backup.extractfile('./autowonder.env').read())

    def test_backup_refuses_unexpected_active_release(self):
        current = self.root / 'i-a/opt/current'
        other = current.parent / 'releases' / ('c' * 12)
        other.mkdir()
        (other / 'auto-wonder.jar').write_bytes(b'wrong release')
        current.unlink()
        current.symlink_to(other)
        result = self.run_script('upgrade-operations.sh', 'upgrade-backup')
        self.assertNotEqual(0, result.returncode, 'backed up an unplanned active release')
        self.assertFalse((current.parent / 'upgrade-rollback-backup.tar.gz').exists())

    def test_confirmed_rollback_restores_checkpointed_environment(self):
        self.backup()
        (self.root / 'i-a/etc/autowonder.env').write_text('TARGET=value\n')
        result = self.run_script('upgrade-operations.sh', 'rollback-upgrade', '--confirm-rollback')
        self.assertEqual(0, result.returncode, result.stderr + (self.root / 'remote.stderr').read_text())
        self.assertEqual('ORIGINAL=value\n', (self.root / 'i-a/etc/autowonder.env').read_text())

    def test_rollback_refuses_incomplete_backup_coverage_before_remote_changes(self):
        self.backup()
        data = json.loads(self.manifest.read_text())
        data['upgrade']['rollbackBackup']['nodes'].pop()
        self.manifest.write_text(json.dumps(data))
        calls = (self.root / 'calls').read_text()
        result = self.run_script('upgrade-operations.sh', 'rollback-upgrade', '--confirm-rollback')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(calls, (self.root / 'calls').read_text())

    def test_staging_requires_backup_for_every_target(self):
        data = json.loads(self.manifest.read_text())
        active_env = self.root / 'active.env'
        active_env.write_text('AUTOWONDER_SECRET_MASTER_KEY=synthetic-master\n')
        active_env.chmod(0o600)
        data.setdefault('localContext', {})['activeEnvFile'] = str(active_env)
        candidate_text = 'AUTOWONDER_SECRET_MASTER_KEY=synthetic-master\nAUTOWONDER_RUNTIME_RECOMMENDED_VERSION=0.7.0\n'
        candidate_hash = hashlib.sha256(candidate_text.encode()).hexdigest()
        data['upgrade'].update(environmentContractChecked=True, environmentValidated=True,
                               targetRecommendedRuntimeVersion='0.7.0',
                               environmentCandidateSha256=candidate_hash)
        data['runtimeConfig'] = {'prepared': True, 'recommendedRuntimeVersion': '0.7.0',
                                'envSha256': candidate_hash}
        self.manifest.write_text(json.dumps(data))
        self.approve()
        data = json.loads(self.manifest.read_text())
        data['runtimeConfig']['planFingerprint'] = data['upgrade']['planFingerprint']
        self.manifest.write_text(json.dumps(data))
        self.backup()
        data = json.loads(self.manifest.read_text())
        data['upgrade']['rollbackBackup']['nodes'].pop()
        self.manifest.write_text(json.dumps(data))
        release = self.root / 'i-a/opt/releases' / ('a' * 12)
        for name in ('autowonder-schema.sql', 'autowonder-community-templates.sql'):
            (release / name).write_text('fixture')
        candidate = self.root / 'candidate.env'
        candidate.write_text(candidate_text)
        candidate.chmod(0o600)
        calls = (self.root / 'calls').read_text()
        result = self.run_script('stage-upgrade.sh', '--env-file', str(candidate), '--release-dir', str(release))
        self.assertNotEqual(0, result.returncode)
        self.assertIn('every target', result.stderr)
        self.assertEqual(calls, (self.root / 'calls').read_text())

    def test_rollback_rejects_replaced_archive_before_restoring_files(self):
        self.backup()
        archive = self.root / 'i-a/opt/upgrade-rollback-backup.tar.gz'
        # A different, internally valid backup must not replace the checkpointed one.
        with tarfile.open(archive, 'r:gz') as old:
            old.extractall(self.root / 'replacement', filter='data')
        directory = self.root / 'replacement'
        for metadata in directory.rglob('._*'):
            metadata.unlink()
        (directory / 'autowonder.env').write_text('REPLACED=value\n')
        subprocess.run(['bash', '-c', 'find . -type f ! -name CHECKSUMS -print0 | sort -z | xargs -0 sha256sum >CHECKSUMS'],
                       cwd=directory, check=True)
        with tarfile.open(archive, 'w:gz') as replacement:
            replacement.add(directory, arcname='.')
        result = self.run_script('upgrade-operations.sh', 'rollback-upgrade', '--confirm-rollback')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('ORIGINAL=value\n', (self.root / 'i-a/etc/autowonder.env').read_text(),
                         'rollback installed an archive that differs from recorded SHA-256')


if __name__ == '__main__':
    unittest.main()
