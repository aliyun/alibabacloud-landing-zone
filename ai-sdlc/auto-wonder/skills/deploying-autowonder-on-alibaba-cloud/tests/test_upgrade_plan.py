import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "plan-upgrade.sh"


class UpgradePlanTest(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.root = Path(self.tempdir.name)
        self.remote = self.root / "remote.git"
        self.source = self.root / "source"
        self.git("init", "--bare", str(self.remote), cwd=self.root)
        self.git("clone", str(self.remote), str(self.source), cwd=self.root)
        self.git("config", "user.email", "upgrade-test@example.invalid")
        self.git("config", "user.name", "Upgrade Test")
        self.write("src/main/resources/application.yml", "service:\n  value: ${OLD_ENV:old}\n")
        self.write("docs/community/application.env.example", "OLD_ENV=\n")
        self.write("docs/migration/README.md", "migration contract\n")
        self.git("add", ".")
        self.git("commit", "-m", "baseline")
        self.git("branch", "-M", "community")
        self.git("push", "-u", "origin", "community")
        self.old_commit = self.git("rev-parse", "HEAD").stdout.strip()

    def tearDown(self):
        self.tempdir.cleanup()

    def git(self, *args, cwd=None):
        return subprocess.run(
            ["git", *args],
            cwd=cwd or self.source,
            text=True,
            capture_output=True,
            check=True,
        )

    def write(self, relative, content):
        path = self.source / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def manifest(self):
        path = self.root / "manifest.json"
        path.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "mode": "new",
                    "phase": "accepted",
                    "status": "accepted",
                    "repositoryUrl": str(self.remote),
                    "repositoryRef": "community",
                    "repositoryCommit": self.old_commit,
                    "deployment": {"activeCommit": self.old_commit},
                    "upgradeInventory": {
                        "status": "verified",
                        "activeCommit": self.old_commit,
                    },
                }
            ),
            encoding="utf-8",
        )
        return path

    def run_plan(self, manifest, *extra):
        return subprocess.run(
            [
                str(SCRIPT),
                "--manifest",
                str(manifest),
                "--source-dir",
                str(self.source),
                "--target-ref",
                "community",
                *extra,
            ],
            text=True,
            capture_output=True,
            env={**os.environ, "LC_ALL": "C"},
        )

    def publish_target(self, migration_name="V1__add_upgrade_state.sql"):
        self.write(
            "src/main/resources/application.yml",
            "service:\n  value: ${OLD_ENV:changed}\n  required: ${NEW_REQUIRED:}\n",
        )
        self.write("docs/community/application.env.example", "OLD_ENV=\nNEW_REQUIRED=\n")
        migration = "ALTER TABLE workitem ADD COLUMN upgrade_state VARCHAR(32);\n"
        self.write(f"docs/migration/{migration_name}", migration)
        self.git("add", ".")
        self.git("commit", "-m", "feat: add upgrade state")
        target = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("push", "origin", "community")
        self.git("reset", "--hard", self.old_commit)
        return target, migration

    def test_plans_linear_upgrade_with_env_and_migration_metadata(self):
        target, migration = self.publish_target()
        manifest = self.manifest()

        result = self.run_plan(manifest)

        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual("upgrade", plan["mode"])
        self.assertEqual(self.old_commit, plan["upgrade"]["fromCommit"])
        self.assertEqual(target, plan["upgrade"]["toCommit"])
        self.assertTrue(plan["upgrade"]["linearHistory"])
        self.assertEqual(["NEW_REQUIRED"], plan["upgrade"]["environment"]["added"])
        self.assertIn("OLD_ENV", plan["upgrade"]["environment"]["changed"])
        pending = plan["upgrade"]["pendingMigrations"]
        self.assertEqual(1, pending[0]["version"])
        self.assertEqual("docs/migration/V1__add_upgrade_state.sql", pending[0]["file"])
        self.assertEqual(hashlib.sha256(migration.encode()).hexdigest(), pending[0]["sha256"])
        self.assertIn("ALTER", pending[0]["riskOperations"])
        self.assertFalse(plan["upgrade"]["environmentValidated"])
        self.assertFalse(plan["upgrade"]["environmentContractChecked"])
        self.assertEqual("review-required", plan["upgrade"]["databaseCompatibility"]["status"])
        self.assertFalse(plan["upgrade"]["databaseCompatibility"]["rollingAllowed"])
        self.assertTrue(plan["upgrade"]["confirmationRequired"])
        self.assertEqual([], plan["upgrade"]["blockedReasons"])

    def test_replanning_keeps_active_commit_and_pending_migrations(self):
        target, _ = self.publish_target()
        manifest = self.manifest()
        first = self.run_plan(manifest)
        self.assertEqual(0, first.returncode, first.stderr)

        second = self.run_plan(manifest)

        self.assertEqual(0, second.returncode, second.stderr)
        plan = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(self.old_commit, plan["deployment"]["activeCommit"])
        self.assertEqual(self.old_commit, plan["upgrade"]["fromCommit"])
        self.assertEqual(target, plan["upgrade"]["toCommit"])
        self.assertEqual(1, len(plan["upgrade"]["pendingMigrations"]))

    def test_candidate_environment_is_recorded_as_validated(self):
        self.publish_target()
        manifest = self.manifest()
        env_file = self.root / "candidate.env"
        env_file.write_text("NEW_REQUIRED=configured\n", encoding="utf-8")
        env_file.chmod(0o600)

        result = self.run_plan(manifest, "--env-file", str(env_file))

        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertTrue(plan["upgrade"]["environmentContractChecked"])
        self.assertFalse(plan["upgrade"]["environmentValidated"])
        self.assertEqual(
            hashlib.sha256(env_file.read_bytes()).hexdigest(),
            plan["upgrade"]["environmentPlanSha256"],
        )

    def test_runtime_managed_environment_does_not_block_upgrade_plan(self):
        self.write(
            "src/main/resources/application.yml",
            "service:\n  value: ${OLD_ENV:old}\n  version: ${AUTOWONDER_VERSION:x.x.x}\n",
        )
        self.write(
            "docs/community/application.env.example",
            "OLD_ENV=\nAUTOWONDER_VERSION=x.x.x\n",
        )
        self.git("add", ".")
        self.git("commit", "-m", "add managed application version")
        self.git("push", "origin", "community")
        self.git("reset", "--hard", self.old_commit)
        env_file = self.root / "candidate.env"
        env_file.write_text("OLD_ENV=configured\n", encoding="utf-8")
        env_file.chmod(0o600)
        manifest = self.manifest()

        result = self.run_plan(manifest, "--env-file", str(env_file))

        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(
            ["AUTOWONDER_VERSION"],
            plan["upgrade"]["environment"]["added"],
        )
        self.assertEqual([], plan["upgrade"]["blockedReasons"])

    def test_disabled_s3_environment_does_not_block_oss_upgrade(self):
        self.publish_s3_target()
        env_file = self.root / "candidate.env"
        env_file.write_text("OLD_ENV=configured\n", encoding="utf-8")
        env_file.chmod(0o600)
        manifest = self.manifest()

        result = self.run_plan(manifest, "--env-file", str(env_file))

        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertIn("S3_ENABLED", plan["upgrade"]["environment"]["added"])
        self.assertEqual([], plan["upgrade"]["blockedReasons"])

    def test_enabled_s3_requires_endpoint_and_credentials(self):
        self.publish_s3_target()
        env_file = self.root / "candidate.env"
        env_file.write_text("OLD_ENV=configured\nS3_ENABLED=true\n", encoding="utf-8")
        env_file.chmod(0o600)
        manifest = self.manifest()

        result = self.run_plan(manifest, "--env-file", str(env_file))

        self.assertNotEqual(0, result.returncode)
        plan = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(
            [
                "required environment value missing: S3_ACCESS_KEY_ID",
                "required environment value missing: S3_ACCESS_KEY_SECRET",
                "required environment value missing: S3_ENDPOINT",
            ],
            plan["upgrade"]["blockedReasons"],
        )

    def test_shell_locals_are_not_application_environment_contract(self):
        self.write(
            "skills/deploying-autowonder-on-alibaba-cloud/scripts/check-runtime.sh",
            """#!/usr/bin/env bash
SCRIPT_DIR=$(pwd)
TEMP_DIRS=()
LC_ALL=C sort </dev/null
IFS= read -r value </dev/null || true
printf '%s' "${REAL_SCRIPT_ENV:-}"
""",
        )
        self.write(
            "docs/community/application.env.example",
            "OLD_ENV=\nDECLARED_ENV=\n",
        )
        self.git("add", ".")
        self.git("commit", "-m", "add runtime environment contract")
        self.git("push", "origin", "community")
        self.git("reset", "--hard", self.old_commit)
        env_file = self.root / "candidate.env"
        env_file.write_text(
            "DECLARED_ENV=configured\nREAL_SCRIPT_ENV=configured\n",
            encoding="utf-8",
        )
        env_file.chmod(0o600)
        manifest = self.manifest()

        result = self.run_plan(manifest, "--env-file", str(env_file))

        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(
            ["DECLARED_ENV", "REAL_SCRIPT_ENV"],
            plan["upgrade"]["environment"]["added"],
        )
        self.assertEqual([], plan["upgrade"]["blockedReasons"])

    def publish_s3_target(self):
        self.write(
            "src/main/resources/application.yml",
            """service:
  value: ${OLD_ENV:old}
s3:
  enabled: ${S3_ENABLED:false}
  endpoint: ${S3_ENDPOINT:}
  public-endpoint: ${S3_PUBLIC_ENDPOINT:}
  region: ${S3_REGION:us-east-1}
  access-key-id: ${S3_ACCESS_KEY_ID:}
  access-key-secret: ${S3_ACCESS_KEY_SECRET:}
  force-path-style: true
""",
        )
        self.git("add", ".")
        self.git("commit", "-m", "add optional S3 configuration")
        self.git("push", "origin", "community")
        self.git("reset", "--hard", self.old_commit)

    def test_rejects_dirty_tracked_source(self):
        self.publish_target()
        self.write("docs/community/application.env.example", "DIRTY=true\n")

        result = self.run_plan(self.manifest())

        self.assertNotEqual(0, result.returncode)
        self.assertIn("tracked changes", result.stderr)

    def test_requires_verified_active_release_inventory(self):
        self.publish_target()
        manifest = self.manifest()
        data = json.loads(manifest.read_text())
        data.pop("upgradeInventory")
        manifest.write_text(json.dumps(data))

        result = self.run_plan(manifest)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("active release inventory", result.stderr)

    def test_blocks_modified_published_migration(self):
        self.write("docs/migration/V1__initial.sql", "ALTER TABLE workitem ADD COLUMN one INT;\n")
        self.git("add", ".")
        self.git("commit", "-m", "add first migration")
        self.git("push", "origin", "community")
        old = self.git("rev-parse", "HEAD").stdout.strip()
        self.write("docs/migration/V1__initial.sql", "ALTER TABLE workitem ADD COLUMN changed INT;\n")
        self.git("commit", "-am", "rewrite migration")
        self.git("push", "origin", "community")
        self.git("reset", "--hard", old)

        manifest = self.manifest()
        data = json.loads(manifest.read_text())
        data["deployment"]["activeCommit"] = old
        data["upgradeInventory"]["activeCommit"] = old
        manifest.write_text(json.dumps(data))
        result = self.run_plan(manifest, "--current-commit", old)

        self.assertNotEqual(0, result.returncode)
        plan = json.loads((self.root / "manifest.json").read_text())
        self.assertIn("published migration changed: docs/migration/V1__initial.sql", plan["upgrade"]["blockedReasons"])

    def test_blocks_duplicate_migration_versions(self):
        self.write("docs/migration/V1__first.sql", "CREATE TABLE first_table(id BIGINT);\n")
        self.write("docs/migration/V1__second.sql", "CREATE TABLE second_table(id BIGINT);\n")
        self.git("add", ".")
        self.git("commit", "-m", "duplicate versions")
        self.git("push", "origin", "community")
        self.git("reset", "--hard", self.old_commit)

        result = self.run_plan(self.manifest())

        self.assertNotEqual(0, result.returncode)
        plan = json.loads((self.root / "manifest.json").read_text())
        self.assertIn("duplicate migration version: 1", plan["upgrade"]["blockedReasons"])

    def test_blocks_non_linear_target(self):
        self.git("checkout", "--orphan", "unrelated")
        self.git("rm", "-rf", ".")
        self.write("docs/migration/README.md", "migration contract\n")
        self.git("add", ".")
        self.git("commit", "-m", "unrelated target")
        self.git("push", "origin", "HEAD:community", "--force")
        self.git("checkout", "-B", "community", self.old_commit)

        result = self.run_plan(self.manifest())

        self.assertNotEqual(0, result.returncode)
        plan = json.loads((self.root / "manifest.json").read_text())
        self.assertFalse(plan["upgrade"]["linearHistory"])
        self.assertIn("target is not a descendant of the active commit", plan["upgrade"]["blockedReasons"])

    def test_plans_upgrade_from_monorepo_project_subdirectory(self):
        product = self.source / "ai-sdlc" / "auto-wonder"
        product.mkdir(parents=True)
        self.git("mv", "src", "docs", str(product))
        self.git("commit", "-m", "move project into monorepo")
        self.git("push", "origin", "community")
        self.old_commit = self.git("rev-parse", "HEAD").stdout.strip()

        application = product / "src/main/resources/application.yml"
        application.write_text(
            "service:\n  value: ${OLD_ENV:changed}\n  required: ${NEW_REQUIRED:}\n",
            encoding="utf-8",
        )
        env_example = product / "docs/community/application.env.example"
        env_example.write_text("OLD_ENV=\nNEW_REQUIRED=\n", encoding="utf-8")
        migration = product / "docs/migration/V036__add_upgrade_state.sql"
        migration.write_text(
            "ALTER TABLE workitem ADD COLUMN upgrade_state VARCHAR(32);\n",
            encoding="utf-8",
        )
        self.git("add", ".")
        self.git("commit", "-m", "add monorepo upgrade")
        target = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("push", "origin", "community")
        self.git("reset", "--hard", self.old_commit)

        manifest = self.manifest()
        result = subprocess.run(
            [
                str(SCRIPT),
                "--manifest",
                str(manifest),
                "--source-dir",
                str(product),
                "--target-ref",
                "community",
            ],
            text=True,
            capture_output=True,
            env={**os.environ, "LC_ALL": "C"},
        )

        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(target, plan["upgrade"]["toCommit"])
        self.assertEqual(["NEW_REQUIRED"], plan["upgrade"]["environment"]["added"])
        self.assertEqual(
            "docs/migration/V036__add_upgrade_state.sql",
            plan["upgrade"]["pendingMigrations"][0]["file"],
        )
        self.assertEqual(36, plan["upgrade"]["pendingMigrations"][0]["version"])


if __name__ == "__main__":
    unittest.main()
