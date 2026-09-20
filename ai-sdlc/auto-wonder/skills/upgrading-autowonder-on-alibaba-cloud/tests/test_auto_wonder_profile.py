import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


UPGRADE_ROOT = Path(__file__).resolve().parents[1]
UPGRADE_INFO = UPGRADE_ROOT / "scripts" / "upgrade_info.py"


class AutoWonderProfileTest(unittest.TestCase):
    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.project = Path(self.tempdir.name).resolve()

    def tearDown(self):
        self.tempdir.cleanup()

    def create_historical_deployment(self, profile_marker):
        deployment = self.project / "deployment"
        terraform = deployment / "terraform"
        terraform.mkdir(parents=True)
        tags = {
            "Project": "AutoWonder",
            "DeploymentId": "aw-prod",
            "Environment": "prod",
            "ManagedBy": "Terraform",
            "Topology": "multi-az-ha",
        }
        manifest = {
            "schemaVersion": 1,
            "status": "accepted",
            "deploymentId": "aw-prod",
            "environment": "prod",
            "region": "cn-hangzhou",
            "repositoryUrl": "https://example.invalid/autowonder.git",
            "repositoryCommit": "a" * 40,
            "tags": tags,
        }
        if profile_marker is not None:
            manifest["cloudProfile"] = profile_marker
        (deployment / "historical-manifest.json").write_text(
            json.dumps(manifest), encoding="utf-8"
        )
        (terraform / "main.tf").write_text(
            'terraform { backend "local" {} }\n', encoding="utf-8"
        )
        (terraform / "terraform.tfstate").write_text("{}\n", encoding="utf-8")
        (terraform / "deployment.auto.tfvars.json").write_text(
            json.dumps(
                {
                    "deployment_id": "aw-prod",
                    "environment": "prod",
                    "region": "cn-hangzhou",
                    "common_tags": tags,
                }
            ),
            encoding="utf-8",
        )
        (terraform / "inventory.json").write_text(
            json.dumps(
                {
                    "region": "cn-hangzhou",
                    "vpc_id": "vpc-1",
                    "ecs_instance_ids": {"zone_a_1": "i-a"},
                    "expected_tags": tags,
                }
            ),
            encoding="utf-8",
        )
        protected_env = deployment / "autowonder.env"
        protected_env.write_text("PASSWORD=protected\n", encoding="utf-8")
        protected_env.chmod(0o600)

    def locate(self):
        return subprocess.run(
            [
                "python3",
                str(UPGRADE_INFO),
                "locate",
                "--project-root",
                str(self.project),
                "--deployment-dir",
                "deployment",
            ],
            text=True,
            capture_output=True,
            env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        )

    def assert_locate_uses_dedicated_profile(self, historical_profile):
        self.create_historical_deployment(historical_profile)

        result = self.locate()

        self.assertEqual(0, result.returncode, result.stderr)
        response = json.loads(result.stdout)
        working = json.loads(Path(response["manifest"]).read_text(encoding="utf-8"))
        self.assertEqual("auto-wonder", response["cloudProfile"])
        self.assertEqual("auto-wonder", working["cloudProfile"])

    def test_historical_named_profile_is_normalized_to_auto_wonder(self):
        self.assert_locate_uses_dedicated_profile("production")

    def test_missing_historical_profile_is_normalized_to_auto_wonder(self):
        self.assert_locate_uses_dedicated_profile(None)

    def test_bash_profile_binding_ignores_manifest_and_ambient_credentials(self):
        manifest = self.project / "manifest.json"
        manifest.write_text(
            json.dumps({"cloudProfile": "production"}), encoding="utf-8"
        )
        command = r'''
source "$1"
configure_cloud_profile "$2"
printf '%s|%s|%s|%s\n' \
  "${CLOUD_PROFILE:-}" "${ALICLOUD_PROFILE:-}" \
  "${ALICLOUD_ACCESS_KEY:-}" "${ALICLOUD_SECRET_KEY:-}"
'''

        result = subprocess.run(
            [
                "bash",
                "-c",
                command,
                "bash",
                str(UPGRADE_ROOT / "scripts" / "upgrade-lib.sh"),
                str(manifest),
            ],
            text=True,
            capture_output=True,
            env={
                **os.environ,
                "HOME": str(self.project / "home"),
                "ALIBABA_CLOUD_CLI_CONFIG_FILE": str(
                    self.project / "missing-aliyun-config.json"
                ),
                "ALICLOUD_ACCESS_KEY": "ambient-ak",
                "ALICLOUD_SECRET_KEY": "ambient-sk",
                "ALICLOUD_SECURITY_TOKEN": "ambient-token",
            },
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("auto-wonder|auto-wonder||", result.stdout.strip())

    def test_windows_cloud_entrypoints_bind_only_auto_wonder(self):
        for name in (
            "refresh-upgrade-info.ps1",
            "verify-deployment-targets.ps1",
            "verify-rds-backup.ps1",
            "upgrade-operations.ps1",
            "stage-upgrade.ps1",
        ):
            with self.subTest(entrypoint=name):
                source = (UPGRADE_ROOT / "scripts" / name).read_text(
                    encoding="utf-8"
                )
                normalized = source.lower()
                if name in ("refresh-upgrade-info.ps1", "verify-deployment-targets.ps1"):
                    self.assertIn("auto-wonder", normalized)
                    self.assertIn("ensure-autowonderaliyunprofile", normalized)
                else:
                    self.assertIn("refresh-approvedupgradetargets", normalized)
                    library = (UPGRADE_ROOT / "scripts" / "windows" / "lib.ps1").read_text(encoding="utf-8").lower()
                    self.assertIn("verify-deployment-targets.ps1", library)
                    self.assertIn("$profile = 'auto-wonder'", library)
                self.assertIsNone(
                    re.search(r"\$profile\s*=\s*if\s*\([^\n]*cloudprofile", normalized),
                    f"{name} still selects a profile from the manifest",
                )
                self.assertIsNone(
                    re.search(r"['\"]default['\"]", normalized),
                    f"{name} still permits the default Alibaba Cloud profile",
                )

    def test_refresh_uses_shared_bootstrap_to_oauth_auto_wonder_then_continues(self):
        binary_dir = self.project / "bin"
        binary_dir.mkdir()
        manifest = self.project / "manifest.json"
        manifest.write_text(
            json.dumps(
                {
                    "deploymentId": "aw-prod",
                    "region": "cn-hangzhou",
                    "cloudProfile": "production",
                }
            ),
            encoding="utf-8",
        )
        aliyun_log = self.project / "aliyun.log"
        sts_count = self.project / "sts-count"
        cli_config = self.project / "aliyun-config.json"
        continued = self.project / "continued"
        real_python = shutil.which("python3")
        self.assertIsNotNone(real_python)

        (binary_dir / "aliyun").write_text(
            r'''#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_ALIYUN_LOG"
case " $* " in
  *" sts GetCallerIdentity "*)
    count=0
    [[ ! -f "$FAKE_STS_COUNT" ]] || count=$(cat "$FAKE_STS_COUNT")
    count=$((count + 1)); printf '%s\n' "$count" >"$FAKE_STS_COUNT"
    ((count > 1)) || exit 1
    printf '{"AccountId":"123456789"}\n'
    ;;
  *" configure get "*)
    printf '{"name":"auto-wonder","mode":"OAuth","access_key_id":"fresh-id","access_key_secret":"fresh-secret","sts_token":"fresh-token"}\n'
    ;;
  *" configure "*)
    [[ " $* " == *" --profile auto-wonder "* ]]
    [[ " $* " == *" --mode OAuth "* ]]
    printf '{"current":"auto-wonder","profiles":[{"name":"auto-wonder","mode":"OAuth","access_key_id":"fresh-id","access_key_secret":"fresh-secret","sts_token":"fresh-token"}]}\n' >"$ALIBABA_CLOUD_CLI_CONFIG_FILE"
    ;;
  *) exit 9 ;;
esac
''',
            encoding="utf-8",
        )
        (binary_dir / "python3").write_text(
            f'''#!/usr/bin/env bash
set -euo pipefail
if [[ ${{1:-}} == */upgrade_info.py ]]; then
  [[ ${{ALICLOUD_ACCESS_KEY:-}} == fresh-id ]]
  [[ ${{ALICLOUD_SECRET_KEY:-}} == fresh-secret ]]
  [[ ${{ALICLOUD_SECURITY_TOKEN:-}} == fresh-token ]]
  : >"$FAKE_CONTINUED"
  printf '{{"status":"refreshed"}}\\n'
else
  exec {real_python} "$@"
fi
''',
            encoding="utf-8",
        )
        for binary in binary_dir.iterdir():
            binary.chmod(0o755)

        # Keep the real authentication flow; isolate private tool installation.
        skills = self.project / 'fixture-skills'
        deploy_scripts = skills / UPGRADE_ROOT.name / 'scripts'
        upgrade_scripts = skills / UPGRADE_ROOT.name / 'scripts'
        deploy_scripts.mkdir(parents=True, exist_ok=True)
        upgrade_scripts.mkdir(parents=True, exist_ok=True)
        for name in ('bootstrap-control-host.sh', 'lib.sh', 'cloud_diagnostics.py'):
            shutil.copyfile(UPGRADE_ROOT / 'scripts' / name, deploy_scripts / name)
        for name in ('refresh-upgrade-info.sh', 'upgrade-lib.sh'):
            shutil.copyfile(UPGRADE_ROOT / 'scripts' / name, upgrade_scripts / name)
        (deploy_scripts / 'runtime-env.sh').write_text(
            'autowonder_runtime_environment() { export AUTOWONDER_PYTHON="$FIXTURE_PYTHON" JAVA_HOME="$HOME/jdk" PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1; }\n')

        clean_environment = {
            key: value
            for key, value in os.environ.items()
            if key
            not in {
                "ALICLOUD_ACCESS_KEY",
                "ALICLOUD_SECRET_KEY",
                "ALICLOUD_SECURITY_TOKEN",
                "ALIBABA_CLOUD_ACCESS_KEY_ID",
                "ALIBABA_CLOUD_ACCESS_KEY_SECRET",
                "ALIBABA_CLOUD_SECURITY_TOKEN",
            }
        }
        result = subprocess.run(
            [
                "bash",
                str(upgrade_scripts / "refresh-upgrade-info.sh"),
                "--project-root",
                str(self.project),
                "--manifest",
                str(manifest),
            ],
            text=True,
            capture_output=True,
            env={
                **clean_environment,
                "PATH": f"{binary_dir}{os.pathsep}{os.environ['PATH']}",
                "HOME": str(self.project / "home"),
                "ALIBABA_CLOUD_CLI_CONFIG_FILE": str(cli_config),
                "FAKE_ALIYUN_LOG": str(aliyun_log),
                "FAKE_STS_COUNT": str(sts_count),
                "FAKE_CONTINUED": str(continued),
                "FIXTURE_PYTHON": str(binary_dir / "python3"),
            },
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(continued.is_file(), "refresh stopped before its core ran")
        calls = aliyun_log.read_text(encoding="utf-8").splitlines()
        sts_calls = [call for call in calls if "sts GetCallerIdentity" in call]
        oauth_calls = [call for call in calls if "configure" in call and "OAuth" in call]
        self.assertGreaterEqual(len(sts_calls), 2)
        self.assertEqual(1, len(oauth_calls))
        self.assertTrue(all("--profile auto-wonder" in call for call in calls))
        refresh_source = (
            UPGRADE_ROOT / "scripts" / "refresh-upgrade-info.sh"
        ).read_text(encoding="utf-8")
        self.assertIn("bootstrap-control-host.sh", refresh_source)


if __name__ == "__main__":
    unittest.main()
