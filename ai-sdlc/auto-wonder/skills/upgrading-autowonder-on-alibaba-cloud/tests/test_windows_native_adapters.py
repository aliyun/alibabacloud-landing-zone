import subprocess
import json
import os
import shutil
import tempfile
import importlib.util
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEPLOY = ROOT / "upgrading-autowonder-on-alibaba-cloud" / "scripts"
UPGRADE = ROOT / "upgrading-autowonder-on-alibaba-cloud" / "scripts"


class WindowsNativeAdapterTests(unittest.TestCase):
    def test_upgrade_info_command_runner_decodes_utf8_on_windows(self):
        module_path = UPGRADE / "upgrade_info.py"
        spec = importlib.util.spec_from_file_location("upgrade_info_windows_test", module_path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        command = [
            sys.executable, "-c",
            "import sys;sys.stderr.buffer.write('配置错误'.encode('utf-8'));sys.exit(1)",
        ]
        with self.assertRaises(module.UpgradeInfoError) as raised:
            module.run_command(command, os.environ.copy())
        self.assertIn("Terraform command failed", str(raised.exception))

    def test_explicit_manifest_under_project_deployment_root_is_accepted(self):
        protected_root = (ROOT.parent / "deployments").resolve() / (
            "adapter-test-" + next(tempfile._get_candidate_names())
        )
        project_root = Path(tempfile.mkdtemp(prefix="aw-project-"))
        try:
            terraform = protected_root / "terraform"
            terraform.mkdir(parents=True)
            (protected_root / "application.env").write_text("SAFE=value\n")
            (protected_root / "backend.hcl").write_text('bucket = "safe"\n')
            manifest = {
                "deploymentId": "adapter-test-175538",
                "environment": "test",
                "region": "cn-beijing",
                "cloudProfile": "default",
                "repositoryUrl": "https://example.invalid/repo.git",
                "repositoryCommit": "a" * 40,
                "tags": {
                    "Project": "AutoWonder", "DeploymentId": "adapter-test-175538",
                    "Environment": "test", "ManagedBy": "Terraform",
                    "Topology": "multi-az-ha",
                },
                "resources": {
                    "ecs_instance_ids": {"zone_a": "i-test"},
                    "expected_tags": {
                        "Project": "AutoWonder", "DeploymentId": "adapter-test-175538",
                        "Environment": "test", "ManagedBy": "Terraform",
                        "Topology": "multi-az-ha",
                    },
                },
                "localContext": {
                    "terraformDirectory": str(terraform),
                    "protectedEnvFile": str(protected_root / "application.env"),
                },
                "terraform": {"stateMode": "oss", "stateReference": str(protected_root / "backend.hcl")},
            }
            manifest_path = protected_root / "deployment-manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            result = subprocess.run(
                [
                    sys.executable, str(UPGRADE / "upgrade_info.py"),
                    "locate", "--project-root", str(project_root), "--manifest", str(manifest_path),
                ], text=True, capture_output=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            working = json.loads(Path(json.loads(result.stdout)["manifest"]).read_text())
            serialized = json.dumps(working).lower()
            self.assertNotIn("safe=value", serialized)

            manifest.pop("localContext")
            manifest["terraform"]["stateReference"] = str(protected_root / "backend.hcl")
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            shutil.rmtree(project_root)
            project_root.mkdir()
            recovered = subprocess.run(
                [
                    sys.executable, str(UPGRADE / "upgrade_info.py"),
                    "locate", "--project-root", str(project_root), "--manifest", str(manifest_path),
                ], text=True, capture_output=True,
            )
            self.assertEqual(0, recovered.returncode, recovered.stderr)
        finally:
            shutil.rmtree(protected_root, ignore_errors=True)
            shutil.rmtree(project_root, ignore_errors=True)

    def test_windows_library_loads_and_exports_required_functions(self):
        if shutil.which("pwsh") is None:
            self.skipTest("PowerShell is unavailable on this control host")
        library = DEPLOY / "windows" / "lib.ps1"
        command = (
            f". '{library}'; "
            + "; ".join(
                f"Get-Command {name} -ErrorAction Stop | Out-Null"
                for name in (
                    "Protect-CurrentUserFile",
                    "Write-AtomicJson",
                    "Import-AliyunCredential",
                    "Invoke-AliyunFlat",
                    "Assert-AliyunIdentity",
                    "Ensure-AutoWonderAliyunProfile",
                    "Get-FileSha256",
                    "New-PrivateTemporaryDirectory",
                    "Assert-ApprovedUpgradePlan",
                    "Assert-VerifiedUpgradeTargets",
                )
            )
        )
        result = subprocess.run(
            ["pwsh", "-NoProfile", "-Command", command],
            text=True,
            capture_output=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)

    def test_windows_bootstrap_declares_complete_dependency_contract(self):
        script = (DEPLOY / "windows" / "bootstrap-control-host.ps1").read_text(
            encoding="utf-8"
        )
        for term in ('git', 'curl.exe', 'tar', 'runtime-bootstrap.ps1', 'tool_runtime.py'):
            self.assertIn(term, script)
        lock = (DEPLOY / 'runtime-lock.tsv').read_text(encoding='utf-8')
        names = {line.split('\t')[0] for line in lock.splitlines() if line and not line.startswith('#')}
        self.assertTrue({'python', 'jdk', 'maven', 'terraform', 'aliyun', 'ossutil', 'jq'} <= names)
        self.assertIn("Ensure-AutoWonderAliyunProfile", script)

    def test_native_upgrade_entrypoints_exist_and_never_invoke_bash(self):
        names = (
            "plan-upgrade.ps1",
            "approve-upgrade-plan.ps1",
            "build-upgrade-release.ps1",
            "upgrade-operations.ps1",
            "stage-upgrade.ps1",
            "verify-rds-backup.ps1",
        )
        for name in names:
            text = (UPGRADE / name).read_text(encoding="utf-8")
            self.assertNotIn("bash", text.lower())

    def test_mutating_windows_entrypoints_bind_plan_and_targets(self):
        for name in ("upgrade-operations.ps1", "stage-upgrade.ps1"):
            text = (UPGRADE / name).read_text(encoding="utf-8")
            self.assertIn("Assert-ApprovedUpgradePlan", text)
            self.assertIn("Assert-VerifiedUpgradeTargets", text)

    def test_windows_rolling_upgrade_is_sequential_and_local_only(self):
        text = (UPGRADE / "upgrade-operations.ps1").read_text(encoding="utf-8")
        remote = (UPGRADE / "remote" / "upgrade_remote.py").read_text(encoding="utf-8")
        self.assertIn("foreach ($instanceId in $instanceIds)", text)
        self.assertIn("Invoke-UpgradePayload", text)
        self.assertIn("127.0.0.1:7001/checkpreload.htm", remote)
        self.assertNotIn("Resolve-DnsName", text)
        self.assertNotIn("PublicIPv4Address", text)
        self.assertIn("ConfirmRollback", text)

    def test_windows_runtime_version_is_bound_before_stage_and_rolling_restart(self):
        planner = (UPGRADE / "plan-upgrade.ps1").read_text(encoding="utf-8")
        policy = (UPGRADE / "upgrade_plan.py").read_text(encoding="utf-8")
        operations = (UPGRADE / "upgrade-operations.ps1").read_text(encoding="utf-8")
        stage = (UPGRADE / "stage-upgrade.ps1").read_text(encoding="utf-8")

        self.assertIn("upgrade_plan.py", planner)
        self.assertIn("src/main/resources/application.yml", policy)
        self.assertIn("targetRecommendedRuntimeVersion", policy)
        self.assertIn("AUTOWONDER_RUNTIME_RECOMMENDED_VERSION", policy)
        self.assertIn("targetRecommendedRuntimeVersion", operations)
        self.assertIn("targetRecommendedRuntimeVersion", stage)
        self.assertIn("runtimeConfig", stage)
        self.assertIn("runtimeConfig", operations.split("'rolling-upgrade'", 1)[1])


if __name__ == "__main__":
    unittest.main()
