"""Run real POSIX backup/rollback against historical Windows JSON archives."""
import hashlib
import json
from pathlib import Path
import tarfile
import unittest

import test_posix_backup_round2 as posix


class PosixLegacyWindowsTests(unittest.TestCase):
    setUp = posix.PosixBackupRetryTests.setUp
    executable = posix.PosixBackupRetryTests.executable
    run_script = posix.PosixBackupRetryTests.run_script
    approve = posix.PosixBackupRetryTests.approve
    backup = posix.PosixBackupRetryTests.backup

    def legacy_archives(self, corrupt=False):
        data = json.loads(self.manifest.read_text())
        result = {}
        for node in ('i-a', 'i-b'):
            snapshot = self.root / ('snapshot-' + node)
            (snapshot / 'release').mkdir(parents=True)
            (snapshot / 'release/auto-wonder.jar').write_text('original jar')
            (snapshot / 'release/autowonder-migrations.tar.gz').write_text('original migrations')
            (snapshot / 'autowonder.env').write_text('ORIGINAL=value\n')
            (snapshot / 'autowonder.service').write_text('original unit\n')
            metadata = {'plan': data['upgrade']['planFingerprint'], 'target': 'b'*40, 'release': 'a'*12,
                        'checksums': {p.relative_to(snapshot).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                                      for p in snapshot.rglob('*') if p.is_file()}}
            (snapshot / 'metadata.json').write_text(json.dumps(metadata))
            if corrupt:
                (snapshot / 'autowonder.env').write_text('corrupt')
            archive = self.root / node / 'opt/upgrade-rollback-backup.tar.gz'
            with tarfile.open(archive, 'w:gz') as package:
                for p in snapshot.iterdir():
                    package.add(p, arcname=p.name)
            result[archive] = archive.read_bytes()
        return result

    def test_posix_reuses_and_restores_original_json_only_snapshot(self):
        archives = self.legacy_archives()
        for node in ('i-a', 'i-b'):
            (self.root / node / 'etc/autowonder.env').write_text('STAGED=value\n')
        self.backup()
        for archive, original in archives.items():
            self.assertEqual(original, archive.read_bytes(), 'must not resnapshot staged environment')
        result = self.run_script('upgrade-operations.sh', 'rollback-upgrade', '--confirm-rollback')
        self.assertEqual(0, result.returncode, result.stderr + (self.root / 'remote.stderr').read_text())
        self.assertEqual('ORIGINAL=value\n', (self.root / 'i-a/etc/autowonder.env').read_text())

    def test_posix_preserves_corrupt_json_archive_and_refuses_replacement(self):
        archives = self.legacy_archives(corrupt=True)
        result = self.run_script('upgrade-operations.sh', 'upgrade-backup')
        self.assertNotEqual(0, result.returncode)
        for archive, original in archives.items():
            self.assertEqual(original, archive.read_bytes())


if __name__ == '__main__':
    unittest.main()
