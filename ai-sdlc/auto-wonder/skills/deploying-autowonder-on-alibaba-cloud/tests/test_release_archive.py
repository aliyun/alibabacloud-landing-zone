import hashlib
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest


sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from release_build import package_migrations


class ReleaseArchiveTest(unittest.TestCase):
    def package_migration(self, directory, with_xattr=False):
        root = Path(directory)
        migration = root / 'docs/migration/V001__example.sql'
        migration.parent.mkdir(parents=True)
        migration.write_bytes(b'CREATE TABLE example (id BIGINT);\n')
        if with_xattr:
            subprocess.run(['xattr', '-w', 'com.autowonder.archive-test', 'test-metadata', str(migration)], check=True)
        archive = root / 'migrations.tar.gz'
        package_migrations(root / 'docs/migration', archive)
        return migration, archive

    def test_archive_preserves_sql_checksum(self):
        with tempfile.TemporaryDirectory() as directory:
            migration, archive = self.package_migration(directory)
            with tarfile.open(archive, 'r:gz') as package:
                contents = package.extractfile('./' + migration.name).read()
            self.assertEqual(hashlib.sha256(migration.read_bytes()).hexdigest(),
                             hashlib.sha256(contents).hexdigest())

    @unittest.skipUnless(sys.platform == 'darwin' and shutil.which('xattr'),
                         'macOS extended attributes are required')
    def test_mac_metadata_does_not_add_pseudo_sql_migrations(self):
        with tempfile.TemporaryDirectory() as directory:
            migration, archive = self.package_migration(directory, with_xattr=True)
            with tarfile.open(archive, 'r:gz') as package:
                entries = [entry for entry in package.getmembers() if entry.isfile()]
                self.assertEqual([entry.name for entry in entries], ['./' + migration.name])
                contents = package.extractfile(entries[0]).read()
            self.assertEqual(hashlib.sha256(migration.read_bytes()).hexdigest(),
                             hashlib.sha256(contents).hexdigest())


if __name__ == '__main__':
    unittest.main()
