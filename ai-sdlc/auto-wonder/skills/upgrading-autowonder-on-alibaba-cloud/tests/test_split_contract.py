import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


UPGRADE_ROOT = Path(__file__).resolve().parents[1]
DEPLOY_ROOT = UPGRADE_ROOT.parent / "deploying-autowonder-on-alibaba-cloud"
class UpgradeSkillSplitContractTests(unittest.TestCase):
    def test_active_commit_prefix_can_be_resolved_from_registered_source_repository(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repository = root / "repository"
            repository.mkdir()
            subprocess.run(["git", "init", "-q", str(repository)], check=True)
            subprocess.run(["git", "-C", str(repository), "config", "user.name", "Test"], check=True)
            subprocess.run(["git", "-C", str(repository), "config", "user.email", "test@example.invalid"], check=True)
            (repository / "README.md").write_text("fixture\n")
            subprocess.run(["git", "-C", str(repository), "add", "README.md"], check=True)
            subprocess.run(["git", "-C", str(repository), "commit", "-q", "-m", "fixture"], check=True)
            commit = subprocess.check_output(
                ["git", "-C", str(repository), "rev-parse", "HEAD"], text=True
            ).strip()
            info = root / "upgrade-info" / "aw-prod-001"
            info.mkdir(parents=True)
            manifest = info / "manifest.json"
            manifest.write_text(json.dumps({
                "localContext": {"sourceDirectory": str(repository)},
                "deployment": {"activeCommit": ""},
                "repositoryCommit": "",
            }))

            result = subprocess.run(
                [
                    "bash", "-c",
                    'source "$1"; resolve_active_commit_from_prefix "$2" "$3"',
                    "bash", str(UPGRADE_ROOT / "scripts" / "upgrade-lib.sh"),
                    str(manifest), commit[:12],
                ],
                text=True,
                capture_output=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(commit, result.stdout.strip())

    def test_upgrade_info_core_replaces_the_legacy_converter(self):
        self.assertTrue((UPGRADE_ROOT / "scripts" / "upgrade_info.py").is_file())
        self.assertFalse((UPGRADE_ROOT / "scripts" / "convert-legacy-deployment.py").exists())

    def test_local_mutation_scripts_require_a_bounded_skill_entrypoint(self):
        common = UPGRADE_ROOT / "scripts" / "internal"
        operations = subprocess.run(
            ["bash", str(common / "operations.sh"), "rolling-upgrade", "--manifest", "/missing"],
            text=True,
            capture_output=True,
        )
        transfer = subprocess.run(
            ["bash", str(common / "release-transfer.sh"), "--stage-only", "--manifest", "/missing"],
            text=True,
            capture_output=True,
        )
        self.assertNotEqual(0, operations.returncode)
        self.assertNotEqual(0, transfer.returncode)
        self.assertIn("bounded skill entrypoint", operations.stderr)
        self.assertIn("bounded skill entrypoint", transfer.stderr)

    def test_skill_bundles_do_not_depend_on_shared_directory(self):
        self.assertFalse((UPGRADE_ROOT.parent / "_shared").exists())
        for skill_root in (UPGRADE_ROOT, DEPLOY_ROOT):
            internal = skill_root / "scripts" / "internal"
            self.assertTrue((internal / "operations.sh").is_file())
            self.assertTrue((internal / "release-transfer.sh").is_file())
            production_files = [skill_root / "SKILL.md"] + list((skill_root / "scripts").rglob("*"))
            combined = "\n".join(
                path.read_text(encoding="utf-8")
                for path in production_files
                if path.is_file() and path.suffix in {".sh", ".ps1", ".py", ".md"}
            )
            self.assertNotIn("/_shared/", combined.replace("\\", "/"))

    def write_fake_aliyun(self, root):
        binary = root / "aliyun"
        binary.write_text("""#!/usr/bin/env bash
set -euo pipefail
case " $* " in
  *" sts GetCallerIdentity "*) printf '{"AccountId":"123456789"}\\n' ;;
  *" configure get "*) printf '{"access_key_id":"test-id","access_key_secret":"test-secret","sts_token":"test-token"}\\n' ;;
  *" ecs DescribeInstances "*)
    ids=${LIVE_CLOUD_IDS:-i-a,i-b}
    if [[ "$*" == *InstanceIds* ]]; then
      for candidate in ${ids//,/ }; do
        [[ "$*" != *"$candidate"* ]] || { ids=$candidate; break; }
      done
    fi
    python3 - "$ids" "${LIVE_DEPLOYMENT_ID:-aw-prod-001}" <<'PY'
import json
import sys
ids = [item for item in sys.argv[1].split(",") if item]
deployment = sys.argv[2]
instances = []
for instance in ids:
    instances.append({
        "InstanceId": instance,
        "VpcAttributes": {"VpcId": "vpc-1"},
        "Tags": {"Tag": [
            {"TagKey": "Project", "TagValue": "AutoWonder"},
            {"TagKey": "DeploymentId", "TagValue": deployment},
            {"TagKey": "Environment", "TagValue": "prod"},
            {"TagKey": "ManagedBy", "TagValue": "Terraform"},
            {"TagKey": "Topology", "TagValue": "multi-az-ha"},
        ]},
    })
print(json.dumps({"Instances": {"Instance": instances}}))
PY
    ;;
  *) exit 9 ;;
esac
""")
        binary.chmod(0o755)
        return binary

    def write_target_manifest(self, path):
        path.write_text(json.dumps({
            "schemaVersion": 1,
            "deploymentId": "aw-prod-001",
            "environment": "prod",
            "region": "cn-hangzhou",
            "cloudProfile": "production",
            "tags": {
                "Project": "AutoWonder",
                "DeploymentId": "aw-prod-001",
                "Environment": "prod",
                "ManagedBy": "Terraform",
                "Topology": "multi-az-ha",
            },
            "resources": {
                "vpc_id": "vpc-1",
                "ecs_instance_ids": {"zone_a_1": "i-a", "zone_b_1": "i-b"},
            },
            "repositoryCommit": "a" * 40,
        }))
        return path

    def test_deployment_entrypoints_reject_upgrade_operations(self):
        operation = subprocess.run(
            [str(DEPLOY_ROOT / "scripts/initialize-and-verify.sh"), "rolling-upgrade"],
            text=True,
            capture_output=True,
        )
        staging = subprocess.run(
            [str(DEPLOY_ROOT / "scripts/deploy-via-cloud-assistant.sh"), "--stage-only"],
            text=True,
            capture_output=True,
        )
        self.assertEqual(2, operation.returncode)
        self.assertEqual(2, staging.returncode)
        self.assertIn("upgrading-autowonder", operation.stderr)
        self.assertIn("upgrading-autowonder", staging.stderr)

    def test_upgrade_entrypoint_rejects_new_deployment_operations(self):
        result = subprocess.run(
            [str(UPGRADE_ROOT / "scripts/upgrade-operations.sh"), "database"],
            text=True,
            capture_output=True,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("Unsupported upgrade operation", result.stderr)

    def test_refresh_wrapper_loads_recorded_profile_credentials_for_terraform(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            binary_dir = root / "bin"
            binary_dir.mkdir()
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({
                "deploymentId": "aw-prod-001",
                "region": "cn-hangzhou",
                "cloudProfile": "production",
            }))
            (binary_dir / "aliyun").write_text("""#!/usr/bin/env bash
printf '%s\\n' "$*" >>"$FAKE_ALIYUN_LOG"
case " $* " in
  *" sts GetCallerIdentity "*) printf '{"AccountId":"123456789"}\\n' ;;
  *" configure get "*) printf '{"access_key_id":"test-id","access_key_secret":"test-secret","sts_token":"test-token","mode":"OAuth"}\\n' ;;
  *) exit 9 ;;
esac
""")
            (binary_dir / "python3").write_text("""#!/usr/bin/env bash
set -euo pipefail
[[ ${ALICLOUD_ACCESS_KEY:-} == test-id ]]
[[ ${ALICLOUD_SECRET_KEY:-} == test-secret ]]
[[ ${ALICLOUD_SECURITY_TOKEN:-} == test-token ]]
[[ -s $FAKE_ALIYUN_LOG ]]
printf '{"status":"refreshed"}\\n'
""")
            for binary in binary_dir.iterdir():
                binary.chmod(0o755)
            aliyun_log = root / "aliyun.log"

            clean_environment = {
                key: value for key, value in os.environ.items()
                if key not in {
                    "ALICLOUD_ACCESS_KEY", "ALICLOUD_SECRET_KEY", "ALICLOUD_SECURITY_TOKEN",
                    "ALIBABA_CLOUD_ACCESS_KEY_ID", "ALIBABA_CLOUD_ACCESS_KEY_SECRET",
                    "ALIBABA_CLOUD_SECURITY_TOKEN",
                }
            }

            result = subprocess.run([
                str(UPGRADE_ROOT / "scripts" / "refresh-upgrade-info.sh"),
                "--project-root", str(root),
                "--manifest", str(manifest),
            ], text=True, capture_output=True, env={
                **clean_environment,
                "PATH": f"{binary_dir}{os.pathsep}{os.environ['PATH']}",
                "FAKE_ALIYUN_LOG": str(aliyun_log),
            })

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual({"status": "refreshed"}, json.loads(result.stdout))
            self.assertTrue(
                aliyun_log.is_file(),
                "refresh wrapper did not load the recorded Alibaba Cloud profile",
            )
            calls = aliyun_log.read_text().splitlines()
            self.assertEqual(2, len(calls))
            self.assertIn("sts GetCallerIdentity", calls[0])
            self.assertIn("configure get", calls[1])
            self.assertTrue(all("--profile production" in call for call in calls))

    def test_resolver_selects_the_only_complete_deployment_manifest_regardless_of_status(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            deployments = root / "deployments"
            deployments.mkdir()
            deployment = deployments / "aw-prod-001"
            deployment.mkdir()
            terraform = deployment / "terraform"
            terraform.mkdir()
            (terraform / "main.tf").write_text('terraform { backend "local" {} }\n')
            state = terraform / "terraform.tfstate"
            state.write_text("{}\n")
            protected_env = deployment / "autowonder.env"
            protected_env.write_text("PASSWORD=protected\n")
            protected_env.chmod(0o600)
            manifest = deployment / "manifest.json"
            manifest.write_text(json.dumps({
                "schemaVersion": "1.0",
                "deploymentId": "aw-prod-001",
                "environment": "prod",
                "region": "cn-hangzhou",
                "cloudProfile": "production",
                "status": "ready",
                "repositoryUrl": "https://example.invalid/autowonder.git",
                "repositoryCommit": "a" * 40,
                "tags": {"Project": "AutoWonder", "DeploymentId": "aw-prod-001", "Environment": "prod", "ManagedBy": "Terraform", "Topology": "multi-az-ha"},
                "localContext": {"sourceDirectory": str(root), "terraformDirectory": str(terraform), "protectedEnvFile": str(protected_env)},
                "stateMode": "local",
                "terraform": {"stateReference": str(state)},
                "resources": {"vpc_id": "vpc-1", "ecs_instance_ids": {"zone_a_1": "i-a", "zone_b_1": "i-b"}},
                "deployment": {"activeCommit": "a" * 40},
            }))
            result = subprocess.run(
                [str(UPGRADE_ROOT / "scripts/resolve-deployment.sh"), "--search-root", str(root)],
                text=True,
                capture_output=True,
                env={**os.environ, "HOME": str(root / "home")},
            )
            self.assertEqual(0, result.returncode, result.stderr)
            response = json.loads(result.stdout)
            self.assertEqual("current-manifest", response["source"])
            self.assertEqual(
                str((root / "upgrade-info" / "aw-prod-001" / "manifest.json").resolve()),
                response["manifest"],
            )

    def test_resolver_ignores_sanitized_evidence_beside_standard_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            deployment = root / "deployments" / "aw-prod-001"
            deployment.mkdir(parents=True)
            terraform = deployment / "terraform"
            terraform.mkdir()
            (terraform / "main.tf").write_text('terraform { backend "local" {} }\n')
            state = terraform / "terraform.tfstate"
            state.write_text("{}\n")
            protected_env = deployment / "autowonder.env"
            protected_env.write_text("PASSWORD=protected\n")
            protected_env.chmod(0o600)
            tags = {"Project": "AutoWonder", "DeploymentId": "aw-prod-001", "Environment": "prod", "ManagedBy": "Terraform", "Topology": "multi-az-ha"}
            manifest_data = {
                "schemaVersion": "1.0",
                "deploymentId": "aw-prod-001",
                "environment": "prod",
                "region": "cn-hangzhou",
                "status": "accepted",
                "cloudProfile": "production",
                "repositoryUrl": "https://example.invalid/autowonder.git",
                "repositoryCommit": "a" * 40,
                "tags": tags,
                "localContext": {"sourceDirectory": str(root), "terraformDirectory": str(terraform), "protectedEnvFile": str(protected_env)},
                "stateMode": "local",
                "terraform": {"stateReference": str(state)},
                "resources": {"vpc_id": "vpc-1", "ecs_instance_ids": {"zone_a_1": "i-a", "zone_b_1": "i-b"}},
                "deployment": {
                    "activeCommit": "a" * 40,
                    "acceptedCommit": "a" * 40,
                },
            }
            manifest = deployment / "manifest.json"
            manifest.write_text(json.dumps(manifest_data))
            evidence_data = dict(manifest_data)
            evidence_data["deploymentId"] = "aw-prod-[REDACTED]"
            (deployment / "sanitized-upgrade-evidence.json").write_text(
                json.dumps(evidence_data)
            )

            result = subprocess.run(
                [str(UPGRADE_ROOT / "scripts/resolve-deployment.sh"), "--search-root", str(root)],
                text=True,
                capture_output=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                str((root / "upgrade-info" / "aw-prod-001" / "manifest.json").resolve()),
                json.loads(result.stdout)["manifest"],
            )

    def test_windows_resolver_uses_the_shared_upgrade_info_core(self):
        resolver = (UPGRADE_ROOT / "scripts" / "resolve-deployment.ps1").read_text()

        self.assertIn("upgrade_info.py", resolver)
        self.assertIn("--deployment-dir", resolver)
        self.assertNotIn("convert-legacy-deployment.py", resolver)

    def test_resolver_stops_when_multiple_deployments_match(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            deployments = root / "deployments"
            deployments.mkdir()
            for suffix in ("one", "two"):
                deployment = deployments / suffix
                deployment.mkdir()
                (deployment / "manifest.json").write_text(json.dumps({
                    "schemaVersion": "1.0",
                    "deploymentId": f"aw-{suffix}",
                    "region": "cn-hangzhou",
                    "status": "accepted",
                    "resources": {"ecs_instance_ids": {"zone_a_1": f"i-{suffix}"}},
                    "deployment": {"activeCommit": "b" * 40},
                }))
            result = subprocess.run(
                [str(UPGRADE_ROOT / "scripts/resolve-deployment.sh"), "--search-root", str(root)],
                text=True,
                capture_output=True,
                env={**os.environ, "HOME": str(root / "home")},
            )
            self.assertEqual(3, result.returncode)
            self.assertIn("multiple complete deployment manifests", result.stderr)

    def test_resolver_uses_required_files_without_manifest_state_gate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            deployments = root / "deployments"
            deployments.mkdir()
            deployment = deployments / "aw-prod-001"
            deployment.mkdir()
            terraform = deployment / "terraform"
            terraform.mkdir()
            (terraform / "main.tf").write_text('terraform { backend "local" {} }\n')
            state = terraform / "terraform.tfstate"
            state.write_text("{}\n")
            protected_env = deployment / "autowonder.env"
            protected_env.write_text("PASSWORD=protected\n")
            protected_env.chmod(0o600)
            manifest = deployment / "manifest.json"
            manifest.write_text(json.dumps({
                "schemaVersion": 1,
                "deploymentId": "aw-prod-001",
                "environment": "prod",
                "region": "cn-hangzhou",
                "cloudProfile": "production",
                "status": "ready",
                "scaling": {"pendingInstanceIds": ["i-new"]},
                "repositoryUrl": "https://example.invalid/autowonder.git",
                "repositoryCommit": "a" * 40,
                "tags": {"Project": "AutoWonder", "DeploymentId": "aw-prod-001", "Environment": "prod", "ManagedBy": "Terraform", "Topology": "multi-az-ha"},
                "localContext": {"sourceDirectory": str(root), "terraformDirectory": str(terraform), "protectedEnvFile": str(protected_env)},
                "stateMode": "local",
                "terraform": {"stateReference": str(state)},
                "resources": {"ecs_instance_ids": {"zone_a_1": "i-a", "zone_b_1": "i-b", "zone_a_2": "i-new"}},
                "deployment": {"activeCommit": "a" * 40},
            }))
            result = subprocess.run([
                str(UPGRADE_ROOT / "scripts/resolve-deployment.sh"),
                "--search-root", str(root),
            ], text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("current-manifest", json.loads(result.stdout)["source"])

    def test_resolver_requests_one_deployment_folder_when_no_registration_exists(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result = subprocess.run([
                str(UPGRADE_ROOT / "scripts/resolve-deployment.sh"),
                "--search-root", str(root),
            ], text=True, capture_output=True)

            self.assertEqual(5, result.returncode)
            self.assertEqual(
                {"status": "deployment-folder-required"},
                json.loads(result.stdout),
            )

    def test_resolver_registers_user_folder_once_and_reuses_upgrade_info(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", str(root)], check=True, capture_output=True)
            subprocess.run(["git", "-C", str(root), "config", "user.email", "legacy@example.invalid"], check=True)
            subprocess.run(["git", "-C", str(root), "config", "user.name", "Legacy Test"], check=True)
            (root / "README.md").write_text("legacy deployment\n")
            subprocess.run(["git", "-C", str(root), "add", "README.md"], check=True)
            subprocess.run(["git", "-C", str(root), "commit", "-m", "legacy release"], check=True, capture_output=True)
            commit = subprocess.run(
                ["git", "-C", str(root), "rev-parse", "HEAD"],
                check=True, text=True, capture_output=True,
            ).stdout.strip()
            repository = "https://github.com/aliyun/alibabacloud-landing-zone.git"
            subprocess.run(["git", "-C", str(root), "remote", "add", "origin", repository], check=True)

            supplied = root / "user-provided-folder"
            legacy = supplied / "prod-history"
            terraform = legacy / "terraform"
            terraform.mkdir(parents=True)
            tags = {
                "Project": "AutoWonder",
                "DeploymentId": "auto-wonder-prod-legacy",
                "Environment": "prod",
                "ManagedBy": "Terraform",
                "Topology": "multi-az-ha",
            }
            historical_manifest = {
                "schemaVersion": 1,
                "status": "accepted",
                "deploymentId": "auto-wonder-prod-legacy",
                "environment": "prod",
                "region": "cn-hangzhou",
                "cloudProfile": "production",
                "repositoryUrl": repository,
                "repositoryCommit": commit,
                "tags": tags,
                "database_password": "historical-secret-value",
            }
            (legacy / "old-manifest.json").write_text(json.dumps(historical_manifest))
            (legacy / "sanitized-upgrade-evidence.json").write_text(json.dumps(historical_manifest))
            (terraform / "inventory.json").write_text(json.dumps({
                "region": "cn-hangzhou",
                "vpc_id": "vpc-legacy",
                "ecs_instance_ids": {"zone_a": "i-a", "zone_b": "i-b"},
                "ecs_private_ips": {"zone_a": "10.0.1.2", "zone_b": "10.0.2.3"},
                "alb_id": "alb-legacy",
                "alb_address": "legacy.example.invalid",
                "expected_tags": tags,
                "rds": {"instance_id": "rm-legacy", "connection": "db.internal", "database": "autowonder", "account": "autowonder", "port": "3306"},
                "redis": {"instance_id": "r-legacy", "connection": "redis.internal", "port": 6379},
                "package_bucket": "legacy-packages",
                "artifact_bucket": "legacy-artifacts",
                "oss": {"package_bucket": "legacy-packages", "artifact_bucket": "legacy-artifacts", "runtime_endpoint": "oss-cn-hangzhou-internal.aliyuncs.com", "control_endpoint": "oss-cn-hangzhou.aliyuncs.com"},
                "sls": {"project": "legacy-logs"},
            }))
            (terraform / "deployment.auto.tfvars.json").write_text(json.dumps({
                "region": "cn-hangzhou",
                "environment": "prod",
                "deployment_id": "auto-wonder-prod-legacy",
                "common_tags": tags,
            }))
            (terraform / "main.tf").write_text('terraform { backend "local" {} }\n')
            (terraform / "terraform.tfstate").write_text("{}\n")
            protected_env = legacy / "autowonder.env"
            protected_env.write_text("SPRING_DATASOURCE_PASSWORD=TopSecretValue\n")
            protected_env.chmod(0o600)

            first = subprocess.run([
                str(UPGRADE_ROOT / "scripts/resolve-deployment.sh"),
                "--search-root", str(root),
                "--deployment-dir", "user-provided-folder",
            ], text=True, capture_output=True)

            self.assertEqual(0, first.returncode, first.stderr)
            first_result = json.loads(first.stdout)
            self.assertEqual("located", first_result["status"])
            manifest = Path(first_result["manifest"])
            self.assertEqual((root / "upgrade-info" / "auto-wonder-prod-legacy" / "manifest.json").resolve(), manifest)
            self.assertEqual(0o600, manifest.stat().st_mode & 0o777)
            converted = json.loads(manifest.read_text())
            self.assertEqual(commit, converted["deployment"]["activeCommit"])
            self.assertEqual({"zone_a": "i-a", "zone_b": "i-b"}, converted["resources"]["ecs_instance_ids"])
            self.assertEqual("user-provided-folder/prod-history/autowonder.env", converted["localContext"]["protectedEnvFile"])
            self.assertNotIn("TopSecretValue", manifest.read_text())
            self.assertNotIn("TopSecretValue", first.stdout + first.stderr)
            self.assertNotIn("historical-secret-value", manifest.read_text() + first.stdout + first.stderr)

            shutil.rmtree(supplied)
            second = subprocess.run([
                str(UPGRADE_ROOT / "scripts/resolve-deployment.sh"),
                "--search-root", str(root),
            ], text=True, capture_output=True)
            self.assertEqual(0, second.returncode, second.stderr)
            self.assertEqual(str(manifest), json.loads(second.stdout)["manifest"])
            self.assertEqual("cache", json.loads(second.stdout)["source"])

    def test_folder_registration_does_not_write_partial_info_when_env_is_missing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            legacy = root / "old"
            terraform = legacy / "terraform"
            terraform.mkdir(parents=True)
            (legacy / "manifest.json").write_text(json.dumps({
                "schemaVersion": 1,
                "status": "accepted",
                "deploymentId": "auto-wonder-prod-old",
                "environment": "prod",
                "region": "cn-hangzhou",
                "repositoryUrl": "https://github.com/aliyun/alibabacloud-landing-zone.git",
                "repositoryCommit": "a" * 40,
                "tags": {"Project": "AutoWonder", "DeploymentId": "auto-wonder-prod-old", "Environment": "prod", "ManagedBy": "Terraform", "Topology": "multi-az-ha"},
                "resources": {"vpc_id": "vpc-old", "ecs_instance_ids": {"zone_a": "i-a"}},
            }))
            (terraform / "main.tf").write_text('terraform { backend "local" {} }\n')
            (terraform / "terraform.tfstate").write_text("{}\n")
            (terraform / "deployment.auto.tfvars.json").write_text(json.dumps({
                "deployment_id": "auto-wonder-prod-old", "environment": "prod", "region": "cn-hangzhou",
                "common_tags": {"Project": "AutoWonder", "DeploymentId": "auto-wonder-prod-old", "Environment": "prod", "ManagedBy": "Terraform", "Topology": "multi-az-ha"},
            }))
            (terraform / "inventory.json").write_text(json.dumps({
                "region": "cn-hangzhou", "vpc_id": "vpc-old", "ecs_instance_ids": {"zone_a": "i-a"},
                "expected_tags": {"Project": "AutoWonder", "DeploymentId": "auto-wonder-prod-old", "Environment": "prod", "ManagedBy": "Terraform", "Topology": "multi-az-ha"},
            }))

            result = subprocess.run([
                str(UPGRADE_ROOT / "scripts/resolve-deployment.sh"),
                "--search-root", str(root),
                "--deployment-dir", "old",
            ], text=True, capture_output=True)

            self.assertNotEqual(0, result.returncode)
            self.assertIn("required file", result.stderr)
            self.assertFalse((root / "upgrade-info").exists())

    def test_target_verification_accepts_only_manifest_bound_ecs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_fake_aliyun(root)
            manifest = self.write_target_manifest(root / "manifest.json")
            env = {**os.environ, "PATH": f"{root}{os.pathsep}{os.environ['PATH']}"}
            command = [
                str(UPGRADE_ROOT / "scripts/verify-deployment-targets.sh"),
                "--manifest", str(manifest),
            ]
            passed = subprocess.run(command, text=True, capture_output=True, env=env)
            self.assertEqual(0, passed.returncode, passed.stderr)
            self.assertEqual(2, len(json.loads(passed.stdout)["nodes"]))

            rejected = subprocess.run(
                command,
                text=True,
                capture_output=True,
                env={**env, "LIVE_DEPLOYMENT_ID": "another-deployment"},
            )
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn("tags do not match", rejected.stderr)

    def test_target_verification_covers_dynamic_three_node_inventory(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_fake_aliyun(root)
            manifest = self.write_target_manifest(root / "manifest.json")
            data = json.loads(manifest.read_text())
            data["resources"]["ecs_instance_ids"]["zone_a_2"] = "i-c"
            manifest.write_text(json.dumps(data))
            result = subprocess.run([
                str(UPGRADE_ROOT / "scripts/verify-deployment-targets.sh"),
                "--manifest", str(manifest),
            ], text=True, capture_output=True, env={
                **os.environ,
                "PATH": f"{root}{os.pathsep}{os.environ['PATH']}",
                "LIVE_CLOUD_IDS": "i-a,i-b,i-c",
            })

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(3, len(json.loads(result.stdout)["nodes"]))

    def test_target_verification_rejects_tagged_cloud_node_outside_terraform(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_fake_aliyun(root)
            manifest = self.write_target_manifest(root / "manifest.json")
            result = subprocess.run([
                str(UPGRADE_ROOT / "scripts/verify-deployment-targets.sh"),
                "--manifest", str(manifest),
            ], text=True, capture_output=True, env={
                **os.environ,
                "PATH": f"{root}{os.pathsep}{os.environ['PATH']}",
                "LIVE_CLOUD_IDS": "i-a,i-b,i-extra",
            })

            self.assertNotEqual(0, result.returncode)
            self.assertIn("outside Terraform inventory", result.stderr)

    def test_mutating_entrypoints_require_the_exact_approved_plan(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_fake_aliyun(root)
            manifest = self.write_target_manifest(root / "manifest.json")
            env = {**os.environ, "PATH": f"{root}{os.pathsep}{os.environ['PATH']}"}
            verify = subprocess.run([
                str(UPGRADE_ROOT / "scripts/verify-deployment-targets.sh"),
                "--manifest", str(manifest),
            ], text=True, capture_output=True, env=env)
            self.assertEqual(0, verify.returncode, verify.stderr)

            data = json.loads(manifest.read_text())
            checkpoint = data["upgrade"]["targetVerification"]
            data["mode"] = "upgrade"
            data["upgrade"] = {
                "targetVerification": checkpoint,
                "fromCommit": "a" * 40,
                "toCommit": "b" * 40,
                "targetRef": "master",
                "remote": "origin",
                "commits": [],
                "changedFiles": [],
                "environment": {"added": [], "removed": [], "changed": []},
                "pendingMigrations": [],
                "blockedReasons": [],
                "environmentContractChecked": True,
                "environmentPlanSha256": "c" * 64,
                "databaseCompatibility": {"status": "not-required", "rollingAllowed": True, "destructive": False},
                "approval": {"status": "pending"},
            }
            manifest.write_text(json.dumps(data))
            fingerprint = subprocess.run([
                "bash", "-c", 'source "$1"; calculate_upgrade_plan_fingerprint "$2"',
                "--", str(UPGRADE_ROOT / "scripts/upgrade-lib.sh"), str(manifest),
            ], text=True, capture_output=True, check=True).stdout.strip()
            data = json.loads(manifest.read_text())
            data["upgrade"]["planFingerprint"] = fingerprint
            manifest.write_text(json.dumps(data))

            blocked = subprocess.run([
                str(UPGRADE_ROOT / "scripts/upgrade-operations.sh"),
                "acceptance", "--manifest", str(manifest),
            ], text=True, capture_output=True)
            self.assertNotEqual(0, blocked.returncode)
            self.assertIn("explicit approval", blocked.stderr)

            wrong = subprocess.run([
                str(UPGRADE_ROOT / "scripts/approve-upgrade-plan.sh"),
                "--manifest", str(manifest), "--fingerprint", "0" * 64,
            ], text=True, capture_output=True)
            self.assertNotEqual(0, wrong.returncode)

            approved = subprocess.run([
                str(UPGRADE_ROOT / "scripts/approve-upgrade-plan.sh"),
                "--manifest", str(manifest), "--fingerprint", fingerprint,
            ], text=True, capture_output=True)
            self.assertEqual(0, approved.returncode, approved.stderr)
            self.assertEqual("approved", json.loads(approved.stdout)["status"])


if __name__ == "__main__":
    unittest.main()
