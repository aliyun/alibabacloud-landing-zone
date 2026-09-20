"""Execute the manifest mutations used at the repository-transition boundary."""
import json
from pathlib import Path
import re
import subprocess
import unittest

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
OLD = 'https://old.example/repository'
NEW = 'https://github.com/example/repository'

class RepositoryPromotionTests(unittest.TestCase):
    def mutations(self, operation):
        source = (SCRIPTS / 'internal/operations.sh').read_text()
        section = source.split('  ' + operation + ')', 1)[1].split('\n    ;;', 1)[0]
        return [block for block in re.findall(r"'([^']*)'", section, re.S)
                if block.startswith('.rollingUpgrade=') or block.startswith('.upgrade.rollback=')]

    def execute(self, expression, repository=OLD, transition=True):
        upgrade = {'fromCommit': 'a' * 40, 'planFingerprint': 'c' * 64}
        if transition:
            upgrade.update(sourceRepositoryUrl=NEW, previousRepositoryUrl=OLD)
        data = {'repositoryUrl': repository, 'repositoryCommit': 'b' * 40, 'upgrade': upgrade}
        result = subprocess.run(['jq', '--argjson', 'nodes', '[]', '--argjson', 'ids', '[]',
                                 '--arg', 'status', 'failed', '--arg', 'failedInstanceId', 'i-b', expression],
                                input=json.dumps(data), capture_output=True, text=True, check=True)
        return json.loads(result.stdout)

    def test_only_final_rollout_promotes_repository(self):
        mutations = self.mutations('rolling-upgrade')
        self.assertGreaterEqual(len(mutations), 2)
        for expression in mutations[:-1]:
            self.assertEqual(OLD, self.execute(expression)['repositoryUrl'])
        result = self.execute(mutations[-1])
        self.assertEqual(NEW, result['repositoryUrl'])
        self.assertEqual(OLD, result['upgrade']['previousRepositoryUrl'])
        self.assertEqual(OLD, self.execute(mutations[-1], transition=False)['repositoryUrl'])

    def test_only_successful_rollback_restores_repository(self):
        mutations = self.mutations('rollback-upgrade')
        self.assertGreaterEqual(len(mutations), 2)
        for expression in mutations[:-1]:
            self.assertEqual(NEW, self.execute(expression, NEW)['repositoryUrl'])
        result = self.execute(mutations[-1], NEW)
        self.assertEqual(OLD, result['repositoryUrl'])
        self.assertEqual(OLD, result['upgrade']['previousRepositoryUrl'])
        self.assertEqual(NEW, self.execute(mutations[-1], NEW, False)['repositoryUrl'])

    def test_real_rollback_promotes_only_after_all_nodes_pass(self):
        import test_posix_backup_round2 as fixtures
        for fail_second_node in (False, True):
            with self.subTest(fail_second_node=fail_second_node):
                fixture = fixtures.PosixBackupRetryTests()
                fixture.setUp()
                self.addCleanup(fixture.doCleanups)
                data = json.loads(fixture.manifest.read_text())
                data['repositoryUrl'] = NEW
                data['upgrade'].update(sourceRepositoryUrl=NEW, previousRepositoryUrl=OLD)
                fixture.manifest.write_text(json.dumps(data))
                fixture.approve()
                fixture.backup()
                if fail_second_node:
                    (fixture.root / 'i-b/opt/upgrade-rollback-backup.tar.gz').write_bytes(b'corrupt')
                result = fixture.run_script('upgrade-operations.sh', 'rollback-upgrade', '--confirm-rollback')
                self.assertEqual(not fail_second_node, result.returncode == 0, result.stderr)
                updated = json.loads(fixture.manifest.read_text())
                self.assertEqual(NEW if fail_second_node else OLD, updated['repositoryUrl'])
                self.assertEqual(OLD, updated['upgrade']['previousRepositoryUrl'])
                self.assertEqual('partial' if fail_second_node else 'passed', updated['upgrade']['rollback']['status'])

    def test_powershell_promotion_is_after_loop_and_never_in_failure_checkpoint(self):
        source = (SCRIPTS / 'upgrade-operations.ps1').read_text()
        rollout = source.split("    'rolling-upgrade' {", 1)[1].split("    'acceptance' {", 1)[0]
        rollback = source.split("    'rollback-upgrade' {", 1)[1]
        for section, key, success in ((rollout, 'sourceRepositoryUrl', "$document.rollingUpgrade=@{status='passed'"),
                                      (rollback, 'previousRepositoryUrl', "$document.upgrade.rollback.status='passed'")):
            assignment = '$document.repositoryUrl=$document.upgrade[' + repr(key) + ']'
            self.assertEqual(1, section.count(assignment))
            self.assertGreater(section.index(assignment), section.index(success))
            self.assertIn("if ($document.upgrade['" + key + "'])", section)
            self.assertNotIn("$document.upgrade['previousRepositoryUrl']=", section)

if __name__ == '__main__':
    unittest.main()
