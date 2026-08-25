import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "upgrade_info.py"


class UpgradeInfoTest(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.project = Path(self.tempdir.name).resolve()

    def tearDown(self):
        self.tempdir.cleanup()

    def create_deployment(self, name="deployment", secret_metadata=False):
        deployment = self.project / name
        terraform = deployment / "terraform"
        terraform.mkdir(parents=True)
        tags = {
            "Project": "AutoWonder",
            "DeploymentId": "aw-prod",
            "Environment": "prod",
            "ManagedBy": "Terraform",
            "Topology": "multi-az-ha",
        }
        metadata = {
            "schemaVersion": 1,
            "status": "accepted",
            "deploymentId": "aw-prod",
            "environment": "prod",
            "region": "cn-hangzhou",
            "cloudProfile": "production",
            "repositoryUrl": "https://example.invalid/autowonder.git",
            "repositoryCommit": "a" * 40,
            "recommendedRuntimeVersion": "0.2.130",
            "tags": tags,
        }
        if secret_metadata:
            metadata["database_password"] = "never-persist-this-value"
            metadata["nested"] = {"access_key_secret": "never-persist-this-secret"}
        (deployment / "old-manifest.json").write_text(json.dumps(metadata), encoding="utf-8")
        (terraform / "main.tf").write_text('terraform { backend "local" {} }\n', encoding="utf-8")
        (terraform / "terraform.tfstate").write_text("{}\n", encoding="utf-8")
        (terraform / "deployment.auto.tfvars.json").write_text(
            json.dumps({
                "deployment_id": "aw-prod",
                "environment": "prod",
                "region": "cn-hangzhou",
                "common_tags": tags,
            }),
            encoding="utf-8",
        )
        (terraform / "inventory.json").write_text(
            json.dumps({
                "region": "cn-hangzhou",
                "vpc_id": "vpc-1",
                "ecs_instance_ids": {"zone_a_1": "i-a", "zone_b_1": "i-b"},
                "expected_tags": tags,
            }),
            encoding="utf-8",
        )
        protected_env = deployment / "autowonder.env"
        protected_env.write_text("SPRING_DATASOURCE_PASSWORD=do-not-read\n", encoding="utf-8")
        protected_env.chmod(0o600)
        return deployment

    def run_cli(self, *args, extra_env=None):
        return subprocess.run(
            ["python3", str(SCRIPT), *args],
            text=True,
            capture_output=True,
            env={
                **os.environ,
                "PYTHONDONTWRITEBYTECODE": "1",
                **(extra_env or {}),
            },
        )

    def locate(self, directory="deployment"):
        return self.run_cli(
            "locate",
            "--project-root", str(self.project),
            "--deployment-dir", directory,
        )

    def persisted_json(self):
        info = self.project / "upgrade-info"
        return "".join(path.read_text(encoding="utf-8") for path in info.rglob("*.json"))

    def terraform_outputs(self, ecs):
        tags = {
            "Project": "AutoWonder",
            "DeploymentId": "aw-prod",
            "Environment": "prod",
            "ManagedBy": "Terraform",
            "Topology": "multi-az-ha",
        }
        return {
            "region": {"value": "cn-hangzhou", "sensitive": False},
            "vpc_id": {"value": "vpc-1", "sensitive": False},
            "vswitch_ids": {"value": ["vsw-a", "vsw-b"], "sensitive": False},
            "ecs_instance_ids": {"value": ecs, "sensitive": False},
            "ecs_private_ips": {"value": {}, "sensitive": False},
            "alb_id": {"value": "alb-1", "sensitive": False},
            "alb_dns_name": {"value": "alb.example.invalid", "sensitive": False},
            "rds": {"value": {"instance_id": "rm-1"}, "sensitive": False},
            "redis": {"value": {"instance_id": "r-1"}, "sensitive": False},
            "oss": {"value": {
                "artifact_bucket": "artifacts",
                "package_bucket": "packages",
            }, "sensitive": False},
            "sls": {"value": {"project": "logs"}, "sensitive": False},
            "expected_tags": {"value": tags, "sensitive": False},
            "application_access_key_secret": {"value": "TF-OUTPUT-SECRET", "sensitive": True},
        }

    def write_fake_terraform(self):
        binary_dir = self.project / "bin"
        binary_dir.mkdir()
        binary = binary_dir / "terraform"
        binary.write_text(
            """#!/usr/bin/env bash
set -euo pipefail
printf '%s|%s\n' "$*" "${TF_DATA_DIR:-}" >>"$FAKE_TERRAFORM_LOG"
case " $* " in
  *" init "*)
    for argument in "$@"; do
      case "$argument" in
        -backend-config=*)
          backend=${argument#-backend-config=}
          mode=$(stat -f '%Lp' "$backend" 2>/dev/null || stat -c '%a' "$backend")
          printf 'BACKEND_MODE=%s\n' "$mode" >>"$FAKE_TERRAFORM_LOG"
          ;;
      esac
    done
    ;;
  *" output -json "*) cat "$FAKE_TERRAFORM_OUTPUT" ;;
esac
""",
            encoding="utf-8",
        )
        binary.chmod(0o755)
        return binary_dir

    def refresh(self, manifest, output, binary_dir, log):
        output_file = self.project / "terraform-output.json"
        output_file.write_text(json.dumps(output), encoding="utf-8")
        return self.run_cli(
            "refresh",
            "--project-root", str(self.project),
            "--manifest", str(manifest),
            extra_env={
                "PATH": f"{binary_dir}{os.pathsep}{os.environ['PATH']}",
                "FAKE_TERRAFORM_OUTPUT": str(output_file),
                "FAKE_TERRAFORM_LOG": str(log),
            },
        )

    def test_locate_persists_private_relative_context_and_manifest(self):
        self.create_deployment()

        result = self.locate()

        self.assertEqual(0, result.returncode, result.stderr)
        response = json.loads(result.stdout)
        manifest = Path(response["manifest"])
        info = self.project / "upgrade-info" / "aw-prod"
        index = json.loads((self.project / "upgrade-info" / "index.json").read_text())
        discovery = json.loads((info / "discovery.json").read_text())
        self.assertEqual("aw-prod", index["activeDeploymentId"])
        self.assertEqual("deployment", index["deployments"]["aw-prod"]["deploymentDirectory"])
        self.assertEqual("deployment/terraform", discovery["terraform"]["workingDirectory"])
        self.assertEqual("deployment/terraform/terraform.tfstate", discovery["terraform"]["localStateFile"])
        self.assertEqual("local", discovery["terraform"]["backendMode"])
        self.assertEqual(info / "manifest.json", manifest)
        working = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual("0.2.130", working["recommendedRuntimeVersion"])
        self.assertEqual(0o700, (self.project / "upgrade-info").stat().st_mode & 0o777)
        for path in (self.project / "upgrade-info").rglob("*.json"):
            self.assertEqual(0o600, path.stat().st_mode & 0o777, path)
        self.assertFalse(any(path.name.startswith(".tmp") for path in info.iterdir()))

    def test_locate_searches_named_top_level_folder_without_status_gate(self):
        deployment = self.create_deployment("customer-folder/prod-history")
        metadata_path = deployment / "old-manifest.json"
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        metadata["status"] = "ready"
        metadata_path.write_text(json.dumps(metadata), encoding="utf-8")

        result = self.locate("customer-folder")

        self.assertEqual(0, result.returncode, result.stderr)
        manifest = Path(json.loads(result.stdout)["manifest"])
        discovery = json.loads((manifest.parent / "discovery.json").read_text())
        self.assertEqual("customer-folder", discovery["deploymentDirectory"])
        self.assertEqual(
            "customer-folder/prod-history/terraform",
            discovery["terraform"]["workingDirectory"],
        )

    def test_locate_binds_backend_from_nested_deployment_root(self):
        deployment = self.create_deployment("customer-folder/prod-history")
        terraform = deployment / "terraform"
        (terraform / "terraform.tfstate").unlink()
        (terraform / "main.tf").write_text(
            'terraform { backend "oss" {} }\n', encoding="utf-8"
        )
        (deployment / "backend.hcl").write_text(
            'bucket = "state-bucket"\n', encoding="utf-8"
        )

        result = self.locate("customer-folder")

        self.assertEqual(0, result.returncode, result.stderr)
        manifest = Path(json.loads(result.stdout)["manifest"])
        discovery = json.loads((manifest.parent / "discovery.json").read_text())
        self.assertEqual(
            "customer-folder/prod-history/backend.hcl",
            discovery["terraform"]["backendConfigFile"],
        )

    def test_secret_bearing_historical_inputs_are_projected_not_rejected(self):
        self.create_deployment(secret_metadata=True)

        result = self.locate()

        self.assertEqual(0, result.returncode, result.stderr)
        combined = result.stdout + result.stderr + self.persisted_json()
        self.assertNotIn("never-persist-this-value", combined)
        self.assertNotIn("never-persist-this-secret", combined)
        self.assertNotIn("database_password", combined)
        self.assertNotIn("access_key_secret", combined)

    def test_rejects_directory_escape_and_external_symlink(self):
        outside = Path(self.tempdir.name).parent / f"{self.project.name}-outside"
        outside.mkdir()
        self.addCleanup(lambda: outside.rmdir())
        escaped = self.run_cli(
            "locate", "--project-root", str(self.project), "--deployment-dir", "../" + outside.name
        )
        self.assertNotEqual(0, escaped.returncode)
        self.assertIn("inside the project root", escaped.stderr)
        link = self.project / "linked"
        link.symlink_to(outside, target_is_directory=True)
        linked = self.locate("linked")
        self.assertNotEqual(0, linked.returncode)
        self.assertIn("symbolic link", linked.stderr)
        self.assertNotIn(str(outside), escaped.stderr + linked.stderr)

    def test_rejects_ambiguous_terraform_roots(self):
        deployment = self.create_deployment()
        second = deployment / "another-terraform"
        second.mkdir()
        (second / "main.tf").write_text('terraform { backend "local" {} }\n', encoding="utf-8")
        (second / "terraform.tfstate").write_text("{}\n", encoding="utf-8")

        result = self.locate()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("multiple Terraform roots", result.stderr)
        self.assertFalse((self.project / "upgrade-info").exists())

    def test_local_state_refresh_reuses_rule_and_detects_third_ecs(self):
        self.create_deployment()
        located = self.locate()
        self.assertEqual(0, located.returncode, located.stderr)
        manifest = Path(json.loads(located.stdout)["manifest"])
        binary_dir = self.write_fake_terraform()
        log = self.project / "terraform.log"

        first = self.refresh(
            manifest,
            self.terraform_outputs({"zone_a_1": "i-a", "zone_b_1": "i-b"}),
            binary_dir,
            log,
        )
        self.assertEqual(0, first.returncode, first.stderr)
        second = self.refresh(
            manifest,
            self.terraform_outputs({
                "zone_a_1": "i-a", "zone_b_1": "i-b", "zone_a_2": "i-c"
            }),
            binary_dir,
            log,
        )

        self.assertEqual(0, second.returncode, second.stderr)
        response = json.loads(second.stdout)
        inventory = json.loads((manifest.parent / "inventory.json").read_text())
        self.assertEqual("scale-out", response["changeType"])
        self.assertEqual(["i-c"], inventory["change"]["addedEcsInstanceIds"])
        self.assertEqual(3, inventory["nodeCount"]["current"])
        self.assertEqual(["i-a", "i-b", "i-c"], inventory["resources"]["ecsInstanceIds"])
        working = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual("packages", working["resources"]["package_bucket"])
        self.assertEqual("artifacts", working["resources"]["artifact_bucket"])
        self.assertNotIn("TF-OUTPUT-SECRET", self.persisted_json() + second.stdout + second.stderr)
        calls = log.read_text()
        self.assertNotIn(" plan ", calls)
        self.assertNotIn(" apply ", calls)
        self.assertNotIn("state pull", calls)
        data_dirs = [line.rsplit("|", 1)[1] for line in calls.splitlines() if "|" in line]
        self.assertTrue(data_dirs)
        self.assertTrue(all(not Path(path).exists() for path in data_dirs))

    def test_refresh_backfills_recommended_runtime_version_for_historical_registration(self):
        self.create_deployment()
        located = self.locate()
        self.assertEqual(0, located.returncode, located.stderr)
        manifest = Path(json.loads(located.stdout)["manifest"])
        historical = json.loads(manifest.read_text(encoding="utf-8"))
        historical.pop("recommendedRuntimeVersion", None)
        manifest.write_text(json.dumps(historical), encoding="utf-8")
        binary_dir = self.write_fake_terraform()
        log = self.project / "terraform.log"

        result = self.refresh(
            manifest,
            self.terraform_outputs({"zone_a_1": "i-a", "zone_b_1": "i-b"}),
            binary_dir,
            log,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        refreshed = json.loads(manifest.read_text(encoding="utf-8"))
        self.assertEqual("0.2.130", refreshed["recommendedRuntimeVersion"])

    def test_oss_backend_with_secrets_uses_private_copy_without_leaking(self):
        deployment = self.create_deployment(secret_metadata=True)
        terraform = deployment / "terraform"
        (terraform / "terraform.tfstate").unlink()
        (terraform / "main.tf").write_text('terraform { backend "oss" {} }\n', encoding="utf-8")
        backend = deployment / "backend.hcl"
        backend.write_text(
            'bucket="state"\naccess_key="SECRET-AK"\nsecret_key="SECRET-SK"\n',
            encoding="utf-8",
        )
        backend.chmod(0o644)
        located = self.locate()
        self.assertEqual(0, located.returncode, located.stderr)
        manifest = Path(json.loads(located.stdout)["manifest"])
        binary_dir = self.write_fake_terraform()
        log = self.project / "terraform.log"

        result = self.refresh(
            manifest,
            self.terraform_outputs({"zone_a_1": "i-a", "zone_b_1": "i-b"}),
            binary_dir,
            log,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        combined = result.stdout + result.stderr + self.persisted_json()
        self.assertNotIn("SECRET-AK", combined)
        self.assertNotIn("SECRET-SK", combined)
        self.assertNotIn("TF-OUTPUT-SECRET", combined)
        self.assertIn("BACKEND_MODE=600", log.read_text())
        self.assertEqual(0o644, backend.stat().st_mode & 0o777)

    def test_register_manifest_updates_complete_inventory_after_scale_out(self):
        deployment = self.create_deployment()
        located = self.locate()
        self.assertEqual(0, located.returncode, located.stderr)
        working = Path(json.loads(located.stdout)["manifest"])
        source = json.loads(working.read_text())
        source["status"] = "ready"
        source["localContext"]["sourceDirectory"] = "."
        source["resources"]["ecs_instance_ids"]["zone_a_2"] = "i-c"
        source_manifest = deployment / "accepted-manifest.json"
        source_manifest.write_text(json.dumps(source), encoding="utf-8")

        result = self.run_cli(
            "register-manifest",
            "--project-root", str(self.project),
            "--manifest", str(source_manifest),
        )

        self.assertEqual(0, result.returncode, result.stderr)
        inventory = json.loads((working.parent / "inventory.json").read_text())
        self.assertEqual(["i-c"], inventory["change"]["addedEcsInstanceIds"])
        self.assertEqual(["i-a", "i-b", "i-c"], inventory["resources"]["ecsInstanceIds"])
        self.assertEqual(3, inventory["nodeCount"]["current"])

    def test_sync_manifest_updates_upgrade_state_and_run_summary(self):
        self.create_deployment()
        located = self.locate()
        self.assertEqual(0, located.returncode, located.stderr)
        manifest = Path(json.loads(located.stdout)["manifest"])
        data = json.loads(manifest.read_text())
        data["status"] = "accepted"
        data["repositoryCommit"] = "b" * 40
        data["upgrade"] = {
            "fromCommit": "a" * 40,
            "toCommit": "b" * 40,
            "planFingerprint": "c" * 64,
            "databaseMigration": {"status": "not-required", "applied": []},
            "rollbackBackup": {"status": "passed", "rawOutput": "must-not-persist"},
        }
        data["rollingUpgrade"] = {
            "status": "passed",
            "nodes": [{"instanceId": "i-a", "status": "passed"}, {"instanceId": "i-b", "status": "passed"}],
        }
        data["acceptance"] = {"ecsLocalHealth": "passed"}
        manifest.write_text(json.dumps(data), encoding="utf-8")

        result = self.run_cli(
            "sync-manifest",
            "--project-root", str(self.project),
            "--manifest", str(manifest),
            "--operation", "acceptance",
        )

        self.assertEqual(0, result.returncode, result.stderr)
        state = json.loads((manifest.parent / "upgrade-state.json").read_text())
        self.assertEqual("accepted", state["status"])
        self.assertEqual("a" * 40, state["sourceCommit"])
        self.assertEqual("b" * 40, state["targetCommit"])
        self.assertTrue(state["rollbackBoundary"]["applicationRollbackAvailable"])
        self.assertIsNotNone(state["latestRunId"])
        summary = manifest.parent / "runs" / state["latestRunId"] / "summary.json"
        self.assertTrue(summary.is_file())
        combined = state.__str__() + summary.read_text()
        self.assertNotIn("must-not-persist", combined)


if __name__ == "__main__":
    unittest.main()
