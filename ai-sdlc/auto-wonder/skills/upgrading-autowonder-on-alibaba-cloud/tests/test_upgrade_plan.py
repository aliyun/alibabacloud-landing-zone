import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "plan-upgrade.sh"
APPROVE = ROOT / "scripts" / "approve-upgrade-plan.sh"
UPGRADE_LIB = ROOT / "scripts" / "upgrade-lib.sh"


class UpgradePlanTest(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.root = Path(self.tempdir.name)
        self.remote = self.root / "remote.git"
        self.source = self.root / "source"
        self.git("init", "--bare", str(self.remote), cwd=self.root)
        self.git("clone", str(self.remote), str(self.source), cwd=self.root)
        self.git("config", "core.hooksPath", "/dev/null")
        self.git("config", "user.email", "upgrade-test@example.invalid")
        self.git("config", "user.name", "Upgrade Test")
        self.write("src/main/resources/application.yml", "service:\n  value: ${OLD_ENV:old}\n")
        self.write("docs/community/application.env.example", "OLD_ENV=\n")
        self.write("docs/migration/README.md", "migration contract\n")
        self.git("add", ".")
        self.git("commit", "-m", "baseline")
        self.git("branch", "-M", "master")
        self.git("push", "-u", "origin", "master")
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
            timeout=30,
        )

    def write(self, relative, content):
        path = self.source / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def manifest(self):
        path = self.root / "manifest.json"
        protected_env = self.root / "autowonder.env"
        protected_env.write_text("OLD_ENV=old\nNEW_REQUIRED=configured\n", encoding="utf-8")
        protected_env.chmod(0o600)
        nodes = [{"instanceId": "i-a", "vpcId": "vpc-1"}]
        tags = {
            "Project": "AutoWonder",
            "DeploymentId": "aw-test",
            "Environment": "test",
            "ManagedBy": "Terraform",
            "Topology": "multi-az-ha",
        }
        target_material = {
            "region": "cn-hangzhou",
            "deploymentId": "aw-test",
            "vpcId": "vpc-1",
            "tags": tags,
            "manifestInstanceIds": ["i-a"],
            "nodes": nodes,
        }
        target_fingerprint = hashlib.sha256(
            (json.dumps(target_material, sort_keys=True, separators=(",", ":")) + "\n").encode()
        ).hexdigest()
        path.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "mode": "new",
                    "phase": "accepted",
                    "status": "accepted",
                    "region": "cn-hangzhou",
                    "environment": "test",
                    "deploymentId": "aw-test",
                    "tags": tags,
                    "resources": {"vpc_id": "vpc-1", "ecs_instance_ids": {"zone_a_1": "i-a"}},
                    "repositoryUrl": str(self.remote),
                    "repositoryRef": "master",
                    "repositoryCommit": self.old_commit,
                    "deployment": {"activeCommit": self.old_commit},
                    "localContext": {"sourceDirectory": str(self.source), "protectedEnvFile": str(protected_env)},
                    "upgradeInventory": {
                        "status": "verified",
                        "activeCommit": self.old_commit,
                        "nodes": [{"instanceId": "i-a", "activeCommitPrefix": self.old_commit[:12]}],
                        "targetVerificationFingerprint": target_fingerprint,
                        "verifiedEpoch": time.time(),
                    },
                    "upgrade": {"targetVerification": {
                        "status": "verified",
                        "fingerprint": target_fingerprint,
                        "nodes": nodes,
                        "verifiedEpoch": time.time(),
                    }},
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
                *extra,
            ],
            text=True,
            capture_output=True,
            env={**os.environ, "LC_ALL": "C"},
            timeout=30,
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
        self.git("push", "origin", "master")
        self.git("reset", "--hard", self.old_commit)
        return target, migration

    def test_plans_linear_upgrade_with_env_and_migration_metadata(self):
        target, migration = self.publish_target()
        manifest = self.manifest()

        result = self.run_plan(manifest)

        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(target, self.git("rev-parse", "HEAD").stdout.strip())
        self.assertEqual("master", plan["upgrade"]["targetRef"])
        self.assertEqual("upgrade", plan["mode"])
        self.assertEqual(self.old_commit, plan["upgrade"]["fromCommit"])
        self.assertEqual(target, plan["upgrade"]["toCommit"])
        self.assertNotIn("linearHistory", plan["upgrade"])
        self.assertEqual(["NEW_REQUIRED"], plan["upgrade"]["environment"]["added"])
        self.assertEqual(["NEW_REQUIRED"], plan["upgrade"]["environment"]["required"])
        self.assertIn("OLD_ENV", plan["upgrade"]["environment"]["changed"])
        pending = plan["upgrade"]["pendingMigrations"]
        self.assertEqual(1, pending[0]["version"])
        self.assertEqual("docs/migration/V1__add_upgrade_state.sql", pending[0]["file"])
        self.assertEqual(hashlib.sha256(migration.encode()).hexdigest(), pending[0]["sha256"])
        self.assertIn("ALTER", pending[0]["riskOperations"])
        self.assertFalse(plan["upgrade"]["environmentValidated"])
        self.assertTrue(plan["upgrade"]["environmentContractChecked"])
        self.assertEqual("review-required", plan["upgrade"]["databaseCompatibility"]["status"])
        self.assertFalse(plan["upgrade"]["databaseCompatibility"]["rollingAllowed"])
        self.assertTrue(plan["upgrade"]["confirmationRequired"])
        self.assertEqual([], plan["upgrade"]["blockedReasons"])

    def test_same_active_and_target_commit_returns_already_latest_without_plan(self):
        manifest = self.manifest()

        result = self.run_plan(manifest)

        self.assertEqual(0, result.returncode, result.stderr)
        status = json.loads(result.stdout)
        self.assertEqual("already-latest", status["status"])
        self.assertEqual(self.old_commit, status["activeCommit"])
        self.assertEqual(self.old_commit, status["targetCommit"])
        self.assertEqual("new", json.loads(manifest.read_text())["mode"])

    def test_force_redeploy_plans_same_active_and_target_commit(self):
        manifest = self.manifest()

        result = self.run_plan(manifest, "--force-redeploy")

        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual("upgrade", plan["mode"])
        self.assertEqual(self.old_commit, plan["upgrade"]["fromCommit"])
        self.assertEqual(self.old_commit, plan["upgrade"]["toCommit"])
        self.assertTrue(plan["upgrade"]["forceRedeploy"])
        self.assertEqual([], plan["upgrade"]["changedFiles"])
        self.assertEqual([], plan["upgrade"]["pendingMigrations"])
        self.assertEqual([], plan["upgrade"]["blockedReasons"])
        self.assertFalse(plan["upgrade"]["confirmationRequired"])
        self.assertRegex(plan["upgrade"]["planFingerprint"], r"^[0-9a-f]{64}$")

        approved = subprocess.run(
            [
                str(APPROVE),
                "--manifest",
                str(manifest),
                "--fingerprint",
                plan["upgrade"]["planFingerprint"],
                "--automatic",
            ],
            text=True,
            capture_output=True,
        )

        self.assertEqual(0, approved.returncode, approved.stderr)
        approval = json.loads(manifest.read_text(encoding="utf-8"))["upgrade"]["approval"]
        self.assertEqual("automatic", approval["mode"])

    def test_automatic_approval_rejects_migration_plan(self):
        self.publish_target()
        manifest = self.manifest()
        planned = self.run_plan(manifest)
        self.assertEqual(0, planned.returncode, planned.stderr)
        plan = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertTrue(plan["upgrade"]["confirmationRequired"])

        approved = subprocess.run(
            [
                str(APPROVE),
                "--manifest",
                str(manifest),
                "--fingerprint",
                plan["upgrade"]["planFingerprint"],
                "--automatic",
            ],
            text=True,
            capture_output=True,
        )

        self.assertNotEqual(0, approved.returncode)
        self.assertIn("impact confirmation", approved.stderr)

    def test_ordinary_application_upgrade_is_approved_automatically(self):
        self.write(
            "src/main/resources/application.yml",
            "service:\n  value: ${OLD_ENV:new-default}\n",
        )
        self.git("add", ".")
        self.git("commit", "-m", "fix: change application behavior")
        self.git("push", "origin", "master")
        self.git("reset", "--hard", self.old_commit)
        manifest = self.manifest()

        planned = self.run_plan(manifest)

        self.assertEqual(0, planned.returncode, planned.stderr)
        plan = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertFalse(plan["upgrade"]["confirmationRequired"])
        approved = subprocess.run(
            [
                str(APPROVE),
                "--manifest",
                str(manifest),
                "--fingerprint",
                plan["upgrade"]["planFingerprint"],
                "--automatic",
            ],
            text=True,
            capture_output=True,
        )
        self.assertEqual(0, approved.returncode, approved.stderr)

    def test_project_source_resolver_finds_unique_monorepo_project(self):
        project = self.source / "nested" / "auto-wonder"
        unit = project / "skills" / "deploying-autowonder-on-alibaba-cloud" / "assets" / "systemd" / "autowonder.service"
        unit.parent.mkdir(parents=True)
        unit.write_text("[Service]\n", encoding="utf-8")
        (project / "VERSION").write_text("0.5.0\n", encoding="utf-8")
        (project / "pom.xml").write_text("<project/>\n", encoding="utf-8")

        result = subprocess.run(
            [
                "bash",
                "-c",
                'source "$1"; resolve_upgrade_project_source_dir "$2"',
                "bash",
                str(UPGRADE_LIB),
                str(self.source),
            ],
            text=True,
            capture_output=True,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(str(project), result.stdout.strip())

    def test_acceptance_state_normalizes_topology_object_to_instance_set(self):
        manifest = self.manifest()
        data = json.loads(manifest.read_text(encoding="utf-8"))
        data["repositoryCommit"] = self.old_commit
        data["deployment"]["activeCommit"] = self.old_commit
        data["rollingUpgrade"] = {
            "status": "passed",
            "targetCommit": self.old_commit,
            "nodes": [
                {"instanceId": "i-a", "status": "passed"},
            ],
        }
        manifest.write_text(json.dumps(data), encoding="utf-8")

        result = subprocess.run(
            [
                "bash",
                "-c",
                'source "$1"; require_upgrade_acceptance_state "$2"',
                "bash",
                str(UPGRADE_LIB),
                str(manifest),
            ],
            text=True,
            capture_output=True,
        )

        self.assertEqual(0, result.returncode, result.stderr)

    def test_verify_plan_approve_chain_preserves_target_binding(self):
        self.publish_target()
        manifest = self.manifest()
        data = json.loads(manifest.read_text())
        data["upgrade"] = {}
        manifest.write_text(json.dumps(data))
        aliyun = self.root / "aliyun"
        aliyun.write_text("""#!/usr/bin/env bash
set -euo pipefail
case " $* " in
  *" sts GetCallerIdentity "*) printf '{"AccountId":"123456789"}\\n' ;;
  *" configure get "*) printf '{"access_key_id":"test-id","access_key_secret":"test-secret","sts_token":"test-token"}\\n' ;;
  *" ecs DescribeInstances "*) printf '%s\\n' '{"Instances":{"Instance":[{"InstanceId":"i-a","VpcAttributes":{"VpcId":"vpc-1"},"Tags":{"Tag":[{"TagKey":"Project","TagValue":"AutoWonder"},{"TagKey":"DeploymentId","TagValue":"aw-test"},{"TagKey":"Environment","TagValue":"test"},{"TagKey":"ManagedBy","TagValue":"Terraform"},{"TagKey":"Topology","TagValue":"multi-az-ha"}]}}]}}' ;;
  *) exit 9 ;;
esac
""")
        aliyun.chmod(0o755)
        verified = subprocess.run([
            str(ROOT / "scripts" / "verify-deployment-targets.sh"),
            "--manifest", str(manifest),
        ], text=True, capture_output=True, env={
            **os.environ, "PATH": f"{self.root}{os.pathsep}{os.environ['PATH']}"
        })
        self.assertEqual(0, verified.returncode, verified.stderr)

        planned = self.run_plan(manifest)
        self.assertEqual(0, planned.returncode, planned.stderr)
        data = json.loads(manifest.read_text())
        self.assertEqual("verified", data["upgrade"]["targetVerification"]["status"])
        fingerprint = data["upgrade"]["planFingerprint"]

        approved = subprocess.run([
            str(APPROVE), "--manifest", str(manifest), "--fingerprint", fingerprint,
        ], text=True, capture_output=True)
        self.assertEqual(0, approved.returncode, approved.stderr)

        data = json.loads(manifest.read_text())
        data["resources"]["ecs_instance_ids"]["zone_b_1"] = "i-b"
        manifest.write_text(json.dumps(data))
        stale = subprocess.run([
            str(APPROVE), "--manifest", str(manifest), "--fingerprint", fingerprint,
        ], text=True, capture_output=True)
        self.assertNotEqual(0, stale.returncode)
        self.assertIn("target verification", stale.stderr)

    def test_resource_set_fingerprint_change_invalidates_approval(self):
        self.publish_target()
        manifest = self.manifest()
        data = json.loads(manifest.read_text())
        data["upgradeInfo"] = {"resourceSetFingerprint": "1" * 64}
        data["upgrade"]["targetVerification"]["resourceSetFingerprint"] = "1" * 64
        data["upgradeInventory"]["resourceSetFingerprint"] = "1" * 64
        manifest.write_text(json.dumps(data))

        planned = self.run_plan(manifest)
        self.assertEqual(0, planned.returncode, planned.stderr)
        data = json.loads(manifest.read_text())
        fingerprint = data["upgrade"]["planFingerprint"]
        data["upgradeInfo"]["resourceSetFingerprint"] = "2" * 64
        manifest.write_text(json.dumps(data))

        stale = subprocess.run([
            str(APPROVE), "--manifest", str(manifest), "--fingerprint", fingerprint,
        ], text=True, capture_output=True)

        self.assertNotEqual(0, stale.returncode)
        self.assertIn("resource set", stale.stderr)

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
        self.git("push", "origin", "master")
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
        self.assertEqual([], plan["upgrade"]["environment"]["required"])
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
        self.assertEqual([], plan["upgrade"]["environment"]["required"])
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
        self.assertEqual(
            ["S3_ACCESS_KEY_ID", "S3_ACCESS_KEY_SECRET", "S3_ENDPOINT"],
            plan["upgrade"]["environment"]["required"],
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
        self.git("push", "origin", "master")
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
        self.assertEqual(["DECLARED_ENV"], plan["upgrade"]["environment"]["required"])
        self.assertEqual([], plan["upgrade"]["blockedReasons"])

    def test_optional_shell_environment_does_not_require_candidate_value(self):
        self.write(
            "skills/deploying-autowonder-on-alibaba-cloud/scripts/check-runtime.sh",
            """#!/usr/bin/env bash
if [[ -n ${AUTOWONDER_RUNTIME_PROBE:-} ]]; then
  printf '%s' "$AUTOWONDER_RUNTIME_PROBE"
fi
if [[ -n ${OSS_SESSION_TOKEN:-} ]]; then
  printf 'temporary token is configured'
fi
""",
        )
        self.git("add", ".")
        self.git("commit", "-m", "add optional shell environment")
        self.git("push", "origin", "master")
        self.git("reset", "--hard", self.old_commit)
        manifest = self.manifest()

        result = self.run_plan(manifest)

        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual(
            ["AUTOWONDER_RUNTIME_PROBE", "OSS_SESSION_TOKEN"],
            plan["upgrade"]["environment"]["added"],
        )
        self.assertEqual([], plan["upgrade"]["environment"]["required"])
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
        self.git("push", "origin", "master")
        self.git("reset", "--hard", self.old_commit)

    def test_rejects_dirty_tracked_source(self):
        self.publish_target()
        self.write("docs/community/application.env.example", "DIRTY=true\n")

        result = self.run_plan(self.manifest())

        self.assertNotEqual(0, result.returncode)
        self.assertIn("tracked changes", result.stderr)

    def test_plans_from_detached_remote_master_without_changing_divergent_branch(self):
        self.publish_target()
        target = self.git("rev-parse", "origin/master").stdout.strip()
        self.git("checkout", "--detach", target)

        result = self.run_plan(self.manifest())

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(target, json.loads(result.stdout)["upgrade"]["toCommit"])
        self.assertEqual("", self.git("branch", "--show-current").stdout.strip())

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
        self.git("push", "origin", "master")
        old = self.git("rev-parse", "HEAD").stdout.strip()
        self.write("docs/migration/V1__initial.sql", "ALTER TABLE workitem ADD COLUMN changed INT;\n")
        self.git("commit", "-am", "rewrite migration")
        self.git("push", "origin", "master")
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
        self.git("push", "origin", "master")
        self.git("reset", "--hard", self.old_commit)

        result = self.run_plan(self.manifest())

        self.assertNotEqual(0, result.returncode)
        plan = json.loads((self.root / "manifest.json").read_text())
        self.assertIn("duplicate migration version: 1", plan["upgrade"]["blockedReasons"])

    def test_plans_when_active_commit_is_not_master_ancestor(self):
        self.git("checkout", "--orphan", "deployed")
        self.git("rm", "-rf", ".")
        self.write("src/main/resources/application.yml", "service:\n  value: ${OLD_ENV:old}\n")
        self.write("docs/community/application.env.example", "OLD_ENV=\n")
        self.write("docs/migration/README.md", "migration contract\n")
        self.git("add", ".")
        self.git("commit", "-m", "deployed from unrelated history")
        deployed_commit = self.git("rev-parse", "HEAD").stdout.strip()
        self.git("checkout", "master")
        target, _ = self.publish_target()

        manifest = self.manifest()
        data = json.loads(manifest.read_text())
        data["repositoryCommit"] = deployed_commit
        data["deployment"]["activeCommit"] = deployed_commit
        data["upgradeInventory"]["activeCommit"] = deployed_commit
        data["upgradeInventory"]["nodes"][0]["activeCommitPrefix"] = deployed_commit[:12]
        manifest.write_text(json.dumps(data))

        result = self.run_plan(manifest)

        self.assertEqual(0, result.returncode, result.stderr)
        plan = json.loads((self.root / "manifest.json").read_text())
        self.assertEqual(deployed_commit, plan["upgrade"]["fromCommit"])
        self.assertEqual(target, plan["upgrade"]["toCommit"])
        self.assertNotIn("linearHistory", plan["upgrade"])
        self.assertEqual([], plan["upgrade"]["blockedReasons"])

    def test_plans_upgrade_from_monorepo_project_subdirectory(self):
        product = self.source / "ai-sdlc" / "auto-wonder"
        product.mkdir(parents=True)
        self.git("mv", "src", "docs", str(product))
        self.git("commit", "-m", "move project into monorepo")
        self.git("push", "origin", "master")
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
        self.git("push", "origin", "master")
        self.git("reset", "--hard", self.old_commit)

        manifest = self.manifest()
        result = subprocess.run(
            [
                str(SCRIPT),
                "--manifest",
                str(manifest),
                "--source-dir",
                str(product),
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
