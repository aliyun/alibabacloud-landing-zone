"""Repository-only protocol test: independently installed Skill packages exchange state.

No Skill test imports the other package. Each production CLI runs in a fresh
isolated Python process; only a manifest and sealed artifact bytes cross roots.
Cloud inventory is a local fixture, not an assertion about real cloud resources.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
import zipfile


REPOSITORY = Path(__file__).resolve().parents[2]
DEPLOY = 'deploying-autowonder-on-alibaba-cloud'
UPGRADE = 'upgrading-autowonder-on-alibaba-cloud'
SQL = 'CREATE TABLE interop_fixture (id BIGINT PRIMARY KEY);\n'
APPLICATION = ('service:\n  value: ${EXAMPLE_VALUE:default}\n'
               'autowonder:\n  runtime:\n'
               '    recommended-version: ${AUTOWONDER_RUNTIME_RECOMMENDED_VERSION:0.2.138}\n')


class IndependentSkillInteropTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='aw-skill-interop-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.producer = self.root / 'producer'
        self.consumer = self.root / 'consumer'
        self.environment = {key: value for key, value in os.environ.items()
                            if not key.startswith(('ALICLOUD_', 'ALIBABA_CLOUD_', 'OSS_', 'TF_VAR_'))}
        self.environment.update(PYTHONPATH='', PYTHONUTF8='1', PYTHONDONTWRITEBYTECODE='1')
        denied = self.root / 'denied-tools'
        denied.mkdir()
        for name in ('aliyun', 'ossutil', 'git'):
            stub = denied / (name + '.cmd' if os.name == 'nt' else name)
            stub.write_text('@exit /b 97\n' if os.name == 'nt' else '#!/bin/sh\nexit 97\n')
            stub.chmod(0o700)
        self.environment['PATH'] = str(denied) + os.pathsep + self.environment.get('PATH', '')
        for root, skill in ((self.producer, DEPLOY), (self.consumer, UPGRADE)):
            shutil.copytree(REPOSITORY / 'skills' / skill, root / 'skills' / skill,
                            ignore=shutil.ignore_patterns('__pycache__', 'tests', '.autowonder-tools'))
            for relative, text in {'VERSION': '1.2.3\n', 'pom.xml': '<project/>\n',
                    'src/main/resources/application.yml': APPLICATION,
                    'docs/community/application.env.example': 'EXAMPLE_VALUE=\n',
                    'docs/migration/V1__interop_fixture.sql': SQL}.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding='utf-8')
        self.assertFalse((self.producer / 'skills' / UPGRADE).exists())
        self.assertFalse((self.consumer / 'skills' / DEPLOY).exists())

        state = self.producer / 'upgrade-info'
        sealed = state / 'sealed'
        sealed.mkdir(parents=True)
        with zipfile.ZipFile(sealed / 'auto-wonder.jar', 'w') as archive:
            archive.writestr('BOOT-INF/classes/application.yml', APPLICATION)
        with tarfile.open(sealed / 'autowonder-migrations.tar.gz', 'w:gz') as archive:
            archive.add(self.producer / 'docs/migration/V1__interop_fixture.sql', arcname='./V1__interop_fixture.sql')
        artifacts = {'releaseDirectory': str(sealed)}
        for key, filename in (('jar', 'auto-wonder.jar'), ('migrations', 'autowonder-migrations.tar.gz')):
            artifacts[key] = {'name': filename, 'sha256': hashlib.sha256((sealed / filename).read_bytes()).hexdigest()}
        self.active = artifacts['jar']['sha256'][:40]
        manifest = state / 'manifest.json'
        manifest.write_text(json.dumps({'schemaVersion': 1, 'mode': 'new',
            'repositoryCommit': self.active, 'releaseVersion': '1.2.3',
            'source': {'kind': 'workspace', 'releaseId': self.active, 'gitValidation': 'disabled'},
            'artifacts': artifacts}), encoding='utf-8')
        result = self.cli(self.producer, DEPLOY, 'seal', '--manifest', str(manifest),
                          '--source-dir', str(self.producer))
        self.assertEqual(0, result.returncode, result.stderr)
        produced = json.loads(manifest.read_text())
        self.baseline = produced['source']['baseline']
        self.assertEqual(self.active, self.baseline['releaseId'])
        self.assertEqual({'docs/migration/V1__interop_fixture.sql': hashlib.sha256(SQL.encode()).hexdigest()},
                         self.baseline['migrations'])

        # Restore only the public protocol data; remove the producer entirely so
        # neither imports nor stale absolute artifact paths can reach its package.
        shutil.copytree(state, self.consumer / 'upgrade-info')
        shutil.rmtree(self.producer)
        self.manifest = self.consumer / 'upgrade-info/manifest.json'
        self.sealed = self.consumer / 'upgrade-info/sealed'
        candidate = self.consumer / 'upgrade-info/candidate.env'
        # Simulate the existing protected escrow record restored with this
        # deployment. Real operators must reuse, not invent, its generation ID.
        candidate.write_text('EXAMPLE_VALUE=fixture\n'
                             'AUTOWONDER_SECRET_KEY_GENERATION_ID='
                             '985bc0a7-5abf-4fc7-a612-2549c5a7848d\n', encoding='utf-8')
        candidate.chmod(0o600)
        nodes = [{'instanceId': 'i-fixture', 'vpcId': 'vpc-fixture'}]
        tags = {'Project': 'AutoWonder', 'DeploymentId': 'interop', 'Environment': 'test',
                'ManagedBy': 'Terraform', 'Topology': 'multi-az-ha'}
        material = {'region': 'cn-hangzhou', 'deploymentId': 'interop', 'vpcId': 'vpc-fixture',
                    'tags': tags, 'manifestInstanceIds': ['i-fixture'], 'nodes': nodes}
        fingerprint = hashlib.sha256((json.dumps(material, sort_keys=True, separators=(',', ':')) + '\n').encode()).hexdigest()
        produced.update(region='cn-hangzhou', environment='test', deploymentId='interop', tags=tags,
            resources={'vpc_id': 'vpc-fixture', 'ecs_instance_ids': {'zone_a': 'i-fixture'}},
            deployment={'activeCommit': self.active},
            localContext={'protectedEnvFile': str(candidate)},
            upgrade={'targetVerification': {'status': 'verified', 'fingerprint': fingerprint,
                                            'nodes': nodes, 'verifiedEpoch': time.time()}},
            upgradeInventory={'status': 'verified', 'activeCommit': self.active,
                'targetVerificationFingerprint': fingerprint, 'verifiedEpoch': time.time(),
                'nodes': [{'instanceId': 'i-fixture', 'activeCommitPrefix': self.active[:12],
                          'jarSha256': artifacts['jar']['sha256'],
                          'migrationsSha256': artifacts['migrations']['sha256']}]})
        self.manifest.write_text(json.dumps(produced), encoding='utf-8')

    def cli(self, root, skill, *arguments):
        return subprocess.run([sys.executable, '-I', '-B',
            str(root / 'skills' / skill / 'scripts/upgrade_plan.py'), *arguments],
            cwd=root, env=self.environment, text=True, capture_output=True, timeout=30)

    def plan(self, *extra):
        return self.cli(self.consumer, UPGRADE, 'plan', '--manifest', str(self.manifest),
                        '--source-dir', str(self.consumer), '--baseline-dir', str(self.sealed),
                        '--workspace-current-content', *extra)

    def test_deployment_seal_is_consumed_by_independent_same_version_redeploy(self):
        unforced = self.plan()
        self.assertNotEqual(0, unforced.returncode)
        self.assertIn('explicit forced redeployment', unforced.stderr)
        first = self.plan('--force-redeploy')
        document = json.loads(self.manifest.read_text())
        self.assertEqual(0, first.returncode, first.stderr +
                         json.dumps(document.get('upgrade', {}).get('blockedReasons', [])))
        plan = document['upgrade']
        self.assertEqual([], plan['pendingMigrations'])
        self.assertEqual([], plan['blockedReasons'])
        self.assertEqual('workspace-current-content', plan['sourceMode'])
        self.assertEqual('none', plan['remote'])
        self.assertTrue(plan['forceRedeploy'])
        self.assertEqual(self.active, plan['fromCommit'])
        self.assertEqual('sealed-artifacts', plan['sourceBaseline']['kind'])
        self.assertEqual(self.active, plan['sourceBaseline']['releaseId'])
        self.assertEqual(self.baseline, document['deployment']['activeReleaseBaseline']['source']['baseline'])
        second = self.plan('--force-redeploy')
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual(plan['planFingerprint'], json.loads(self.manifest.read_text())['upgrade']['planFingerprint'])

    def test_same_version_redeploy_rejects_new_migration_after_cross_package_restore(self):
        (self.consumer / 'docs/migration/V2__unexpected_change.sql').write_text('ALTER TABLE interop_fixture ADD value INT;\n')
        result = self.plan('--force-redeploy')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('same-version redeployment cannot change migration files', result.stderr)
        self.assertNotIn('planFingerprint', json.loads(self.manifest.read_text())['upgrade'])


if __name__ == '__main__':
    unittest.main()
