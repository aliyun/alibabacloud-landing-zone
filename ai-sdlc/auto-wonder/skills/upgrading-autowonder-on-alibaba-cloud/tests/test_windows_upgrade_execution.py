import base64
import hashlib
import gzip
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
REMOTE = SCRIPTS / "remote"


class WindowsUpgradeExecutionTests(unittest.TestCase):
    def test_compressed_wire_avoids_native_argument_quotes_and_executes(self):
        helper=(SCRIPTS / 'windows-upgrade-common.ps1').read_text()
        prefix='set -o pipefail; printf %s '
        suffix=' | base64 -d | gzip -d | python3'
        self.assertIn("$script='"+prefix+"' + $packed + '"+suffix+"'",helper)
        packed=base64.b64encode(gzip.compress(b'print("WIRE_OK")\n')).decode()
        wire=prefix+packed+suffix
        self.assertNotIn('"',wire)
        self.assertNotIn("'",wire)
        result=subprocess.run(['bash','-c',wire],capture_output=True,text=True)
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual('WIRE_OK\n',result.stdout)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="aw-remote-test-")
        self.root = Path(self.temp.name)
        self.app = self.root / "opt/autowonder"
        self.config = self.root / "etc/autowonder"
        self.units = self.root / "etc/systemd/system"
        self.bin = self.root / "bin"
        for path in (self.app / "releases", self.config, self.units, self.bin):
            path.mkdir(parents=True)
        self.old = "a" * 12
        self.target = "b" * 40
        self.plan = "c" * 64
        release = self.app / "releases" / self.old
        release.mkdir()
        (release / "auto-wonder.jar").write_text("old jar")
        (self.app / "current").symlink_to(release)
        (self.config / "autowonder.env").write_text("ORIGINAL=protected\n")
        (self.units / "autowonder.service").write_text("original unit")

    def tearDown(self):
        self.temp.cleanup()

    def run_payload(self, name, values=None, mock_system=False):
        path = REMOTE / "upgrade_remote.py"
        self.assertTrue(path.exists(), f"Missing executable remote payload: {name}")
        text = path.read_text()
        request = {"operation": name.removesuffix(".sh"), "plan": self.plan, "target": self.target, "from": self.old, **(values or {})}
        text = text.replace("@@REQUEST@@", base64.b64encode(json.dumps(request).encode()).decode())
        self.assertNotIn("@@", text, "Unbound payload parameters")
        text = text.replace("/opt/autowonder", str(self.app)).replace("/etc/autowonder", str(self.config)).replace("/etc/systemd/system", str(self.units))
        env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"], TEST_ROOT=str(self.root))
        if mock_system:
            # User/group ownership and service management require Linux root; file
            # transfer, archive extraction, checksums, and switching remain real.
            text = "import os, grp, pwd, types\nos.chown=lambda *a: None\ngrp.getgrnam=lambda n: types.SimpleNamespace(gr_gid=os.getgid())\npwd.getpwnam=lambda n: types.SimpleNamespace(pw_uid=os.getuid())\n" + text
        return subprocess.run(["python3"], input=text, text=True, capture_output=True, env=env, timeout=15)

    def prepare_stage(self):
        result = self.run_payload("upgrade-backup.sh")
        self.assertEqual(0, result.returncode, result.stderr)
        artifacts = self.root / "artifacts"
        artifacts.mkdir()
        contents = {"auto-wonder.jar": "new jar", "autowonder-schema.sql": "schema", "autowonder-community-templates.sql": "templates", "autowonder.service": "new unit", "autowonder.env": "AUTOWONDER_RUNTIME_RECOMMENDED_VERSION=1.2.3\nNEW_SETTING=complete-candidate\n"}
        for name, content in contents.items():
            (artifacts / name).write_text(content)
        migration = artifacts / "migration"
        migration.mkdir()
        (migration / "V2__test.sql").write_text("SELECT 2;")
        with tarfile.open(artifacts / "autowonder-migrations.tar.gz", "w:gz") as archive:
            archive.add(migration, arcname="migration")
        objects = [{"name": file.name, "sha256": hashlib.sha256(file.read_bytes()).hexdigest(), "url": file.as_uri()} for file in artifacts.iterdir() if file.is_file()]
        return {"objects": objects, "backupSha": hashlib.sha256((self.app / "upgrade-rollback-backup.tar.gz").read_bytes()).hexdigest(), "envSha": hashlib.sha256((artifacts / "autowonder.env").read_bytes()).hexdigest(), "runtime": "1.2.3"}

    def test_backup_contains_release_and_protected_files_and_is_retry_safe(self):
        result = self.run_payload("upgrade-backup.sh")
        self.assertEqual(0, result.returncode, result.stderr)
        archive = self.app / "upgrade-rollback-backup.tar.gz"
        self.assertEqual(0o600, archive.stat().st_mode & 0o777)
        unpacked = self.root / "unpacked"
        unpacked.mkdir()
        subprocess.run(["tar", "-xzf", str(archive), "-C", str(unpacked)], check=True)
        self.assertEqual("old jar", (unpacked / "release/auto-wonder.jar").read_text())
        self.assertEqual("ORIGINAL=protected\n", (unpacked / "autowonder.env").read_text())
        self.assertEqual(self.plan, json.loads((unpacked / "metadata.json").read_text())["plan"])
        self.assertIn("BACKUP_SHA256=", result.stdout)
        before = archive.read_bytes()
        (self.config / "autowonder.env").write_text("changed after staging")
        resumed = self.run_payload("upgrade-backup.sh")
        self.assertEqual(0, resumed.returncode, resumed.stderr)
        self.assertEqual(before, archive.read_bytes(), "A retry must preserve the original rollback snapshot")

    def test_stage_rejects_bad_artifact_before_changing_environment(self):
        request = self.prepare_stage()
        request["objects"][0]["sha256"] = "0" * 64
        result = self.run_payload("stage-upgrade.sh", request)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Downloaded artifact checksum mismatch", result.stderr)
        self.assertEqual("ORIGINAL=protected\n", (self.config / "autowonder.env").read_text())
        self.assertEqual(self.old, (self.app / "current").resolve().name)

    def test_stage_transfers_complete_candidate_and_unit_without_activating(self):
        request = self.prepare_stage()
        result = self.run_payload("stage-upgrade.sh", request, mock_system=True)
        self.assertEqual(0, result.returncode, result.stderr)
        release = self.app / "releases" / self.target[:12]
        self.assertEqual("new jar", (release / "auto-wonder.jar").read_text())
        self.assertTrue((release / "migration/V2__test.sql").exists())
        self.assertIn("NEW_SETTING=complete-candidate", (self.config / "autowonder.env").read_text())
        self.assertEqual("new unit", (self.units / "autowonder.service").read_text())
        self.assertEqual(self.old, (self.app / "current").resolve().name)
        self.assertFalse((release / "autowonder.env").exists())

    def mock_services(self):
        for name, body in {"systemctl": "exit 0", "ss": "echo LISTEN", "curl": "echo success"}.items():
            path = self.bin / name
            path.write_text("#!/bin/sh\n" + body + "\n")
            path.chmod(0o755)

    def test_confirmed_rollback_restores_original_release_environment_and_unit(self):
        request = self.prepare_stage()
        staged = self.run_payload("stage-upgrade.sh", request, mock_system=True)
        self.assertEqual(0, staged.returncode, staged.stderr)
        (self.app / "current").unlink()
        (self.app / "current").symlink_to(self.app / "releases" / self.target[:12])
        self.mock_services()
        result = self.run_payload("rollback-upgrade.sh", {"backupSha": request["backupSha"]}, mock_system=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(self.old, (self.app / "current").resolve().name)
        self.assertEqual("ORIGINAL=protected\n", (self.config / "autowonder.env").read_text())
        self.assertEqual("original unit", (self.units / "autowonder.service").read_text())

    def mock_mysql(self):
        script = self.bin / "mysql"
        script.write_text('''#!/usr/bin/env python3
import json, os, re, sys
from pathlib import Path
root=Path(os.environ['TEST_ROOT'])
ledger_path=root/'ledger.json'
ledger=json.loads(ledger_path.read_text()) if ledger_path.exists() else {}
def save(): ledger_path.write_text(json.dumps(ledger))
if '-e' in sys.argv:
    sql=sys.argv[sys.argv.index('-e')+1]
    if sql.startswith('SELECT CONCAT'):
        value=ledger.get(re.search(r'migration_version=(\\d+)',sql).group(1))
        if value: print(value['sha']+' '+str(value['success']))
    elif sql.startswith('INSERT INTO'):
        version, filename, sha=re.search(r"VALUES\\((\\d+),'([^']+)','([^']+)'",sql).groups()
        ledger[version]={'sha':sha,'success':0};save()
    elif sql.startswith('UPDATE autowonder_schema_history'):
        ledger[re.search(r'migration_version=(\\d+)',sql).group(1)]['success']=1;save()
elif '--unbuffered' in sys.argv:
    for line in sys.stdin:
        if 'GET_LOCK' in line or 'RELEASE_LOCK' in line: print('1',flush=True)
        if line.strip()=='quit': break
else:
    content=sys.stdin.read().strip()
    with (root/'executed.log').open('a') as stream: stream.write(content+'\\n')
    if 'FAIL' in content: sys.exit(1)
''')
        script.chmod(0o755)

    def migrations(self, failure=False):
        release = self.app / "releases" / self.target[:12] / "migration"
        release.mkdir(parents=True)
        result=[]
        for version in (10, 2):
            name=f"V{version}__test.sql"
            content="FAIL;" if failure and version==2 else f"SELECT {version};"
            (release / name).write_text(content)
            result.append({"version":version,"file":"docs/migration/"+name,"sha256":hashlib.sha256(content.encode()).hexdigest()})
        (self.config / "autowonder.env").write_text("SPRING_DATASOURCE_URL=jdbc:mysql://db:3306/test\nSPRING_DATASOURCE_USERNAME=user\nSPRING_DATASOURCE_PASSWORD=secret\n")
        return result

    def test_migrations_use_numeric_order_and_ledger_skips_successful_reexecution(self):
        self.mock_mysql()
        migrations=self.migrations()
        result=self.run_payload("database-migrate.sh", {"migrations":migrations})
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual("SELECT 2;\nSELECT 10;\n",(self.root/'executed.log').read_text())
        repeated=self.run_payload("database-migrate.sh", {"migrations":migrations})
        self.assertEqual(0,repeated.returncode,repeated.stderr)
        self.assertEqual("SELECT 2;\nSELECT 10;\n",(self.root/'executed.log').read_text())

    def test_failed_migration_records_failure_and_blocks_following_sql_and_retry(self):
        self.mock_mysql()
        migrations=self.migrations(failure=True)
        result=self.run_payload("database-migrate.sh", {"migrations":migrations})
        self.assertNotEqual(0,result.returncode)
        self.assertEqual("FAIL;\n",(self.root/'executed.log').read_text())
        self.assertEqual(0,json.loads((self.root/'ledger.json').read_text())['2']['success'])
        retry=self.run_payload("database-migrate.sh", {"migrations":migrations})
        self.assertNotEqual(0,retry.returncode)
        self.assertIn('Previous failed migration',retry.stderr)
        self.assertEqual("FAIL;\n",(self.root/'executed.log').read_text())

    def test_entrypoints_refresh_live_targets_and_no_longer_contain_placeholders(self):
        for name in ("build-upgrade-release.ps1", "stage-upgrade.ps1", "upgrade-operations.ps1", "verify-rds-backup.ps1"):
            text = (SCRIPTS / name).read_text()
            self.assertIn("Refresh-ApprovedUpgradeTargets", text, name)
            self.assertNotIn("requires a reviewed non-empty migration implementation", text)
            self.assertNotIn("Rollback execution is intentionally unavailable", text)
        stage = (SCRIPTS / "stage-upgrade.ps1").read_text()
        self.assertIn("Invoke-UpgradeOss", stage)
        self.assertIn("Get-FileSha256", stage)
        build = (SCRIPTS / "build-upgrade-release.ps1").read_text()
        self.assertIn("autowonder.service", build)

    def test_migration_payload_checks_checksum_before_database_mutation(self):
        migration = {"version": 2, "file": "docs/migration/V2__test.sql", "sha256": "0" * 64}
        release = self.app / "releases" / self.target[:12] / "migration"
        release.mkdir(parents=True)
        (release / "V2__test.sql").write_text("SELECT 1;")
        (self.config / "autowonder.env").write_text("SPRING_DATASOURCE_URL=jdbc:mysql://db:3306/test\nSPRING_DATASOURCE_USERNAME=user\nSPRING_DATASOURCE_PASSWORD=secret\n")
        result = self.run_payload("database-migrate.sh", {"migrations": [migration]})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Migration checksum mismatch", result.stderr)
        self.assertNotIn("secret", result.stderr)


if __name__ == "__main__":
    unittest.main()
