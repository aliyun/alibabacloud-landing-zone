import hashlib
import json
import os
from pathlib import Path
import subprocess
import shutil
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
PROFILE = "auto-wonder"


class AutoWonderProfileContractTest(unittest.TestCase):
    def test_manifest_template_pins_auto_wonder_profile(self):
        manifest = json.loads(
            (ROOT / "assets/templates/deployment-manifest.json").read_text(encoding="utf-8")
        )
        self.assertEqual(PROFILE, manifest.get("cloudProfile"))

    def test_preflight_rejects_any_explicit_profile_other_than_auto_wonder(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.write_manifest(root / "manifest.json")
            source = self.write_source(root / "source")

            result = subprocess.run(
                [
                    "bash",
                    str(ROOT / "scripts/preflight.sh"),
                    "--manifest",
                    str(manifest),
                    "--source-dir",
                    str(source),
                    "--profile",
                    "default",
                    "--dry-run",
                ],
                text=True,
                capture_output=True,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("auto-wonder", result.stderr)

    def test_missing_posix_profile_runs_oauth_for_auto_wonder_then_revalidates(self):
        self.assert_oauth_recovery(initial_profiles=[])

    def test_expired_posix_profile_runs_oauth_for_auto_wonder_then_revalidates(self):
        self.assert_oauth_recovery(
            initial_profiles=[
                {
                    "name": PROFILE,
                    "access_key_id": "expired-id",
                    "access_key_secret": "expired-secret",
                    "sts_token": "expired-token",
                }
            ]
        )

    def test_shared_posix_cloud_clients_ignore_current_and_default_profiles(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.write_manifest(root / "manifest.json", include_profile=False)
            cli_config = root / "config.json"
            cli_config.write_text(
                json.dumps(
                    {
                        "current": "default",
                        "profiles": [
                            self.profile("default", "default-id"),
                            self.profile(PROFILE, "auto-wonder-id"),
                        ],
                    }
                ),
                encoding="utf-8",
            )
            binary_dir = root / "bin"
            binary_dir.mkdir()
            log = root / "commands.log"
            self.write_executable(
                binary_dir / "aliyun",
                """#!/usr/bin/env bash
printf 'aliyun:%s\n' "$*" >> "$COMMAND_LOG"
printf '{"AccountId":"1234567890123456"}\n'
""",
            )
            self.write_executable(
                binary_dir / "ossutil",
                """#!/usr/bin/env bash
printf 'ossutil:%s:%s\n' "${OSS_ACCESS_KEY_ID:-}" "$*" >> "$COMMAND_LOG"
""",
            )
            self.write_executable(
                binary_dir / "terraform",
                """#!/usr/bin/env bash
printf 'terraform:%s:%s:%s:%s\n' "${ALICLOUD_PROFILE:-}" "${ALICLOUD_ACCESS_KEY:-}" "${ALIBABA_CLOUD_ACCESS_KEY_ID:-}" "$*" >> "$COMMAND_LOG"
""",
            )
            env = os.environ.copy()
            env.update(
                {
                    "PATH": f"{binary_dir}:{env['PATH']}",
                    "COMMAND_LOG": str(log),
                    "ALIBABA_CLOUD_CLI_CONFIG_FILE": str(cli_config),
                    "ALICLOUD_PROFILE": "default",
                    "ALICLOUD_ACCESS_KEY": "stale-id",
                    "ALICLOUD_SECRET_KEY": "stale-secret",
                    "ALICLOUD_SECURITY_TOKEN": "stale-token",
                    "ALIBABA_CLOUD_ACCESS_KEY_ID": "stale-alibaba-id",
                    "ALIBABA_CLOUD_ACCESS_KEY_SECRET": "stale-alibaba-secret",
                    "ALIBABA_CLOUD_SECURITY_TOKEN": "stale-alibaba-token",
                    "OSSUTIL_BIN": str(binary_dir / "ossutil"),
                    "OSSUTIL_CONTRACT": "v2",
                }
            )

            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    """
source "$1"
configure_cloud_profile "$2"
aliyun_cli sts GetCallerIdentity
ossutil_cli stat oss://example
terraform version
""",
                    "bash",
                    str(ROOT / "scripts/lib.sh"),
                    str(manifest),
                ],
                text=True,
                capture_output=True,
                env=env,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            commands = log.read_text(encoding="utf-8").splitlines()
            self.assertIn("aliyun:sts GetCallerIdentity --profile auto-wonder", commands)
            self.assertIn("ossutil:auto-wonder-id:stat oss://example", commands)
            self.assertIn("terraform:auto-wonder:auto-wonder-id::version", commands)
            self.assertFalse(any("default-id" in line or "stale-" in line for line in commands))

    def test_terraform_backend_destroy_uses_only_auto_wonder_profile(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.write_manifest(root / "manifest.json", include_profile=False)
            data = json.loads(manifest.read_text(encoding="utf-8"))
            digest = hashlib.sha256(
                f"{data['accountUid']}|{data['region']}|{data['deploymentId']}".encode()
            ).hexdigest()[:12]
            data["terraform"].update(
                {
                    "mainDestroyVerified": True,
                    "stateBucket": f"aw-tfstate-{data['deploymentId']}-{digest}",
                }
            )
            manifest.write_text(json.dumps(data), encoding="utf-8")
            cli_dir = root / ".aliyun"
            cli_dir.mkdir()
            (cli_dir / "config.json").write_text(
                json.dumps(
                    {
                        "current": "default",
                        "profiles": [
                            self.profile("default", "default-id"),
                            self.profile(PROFILE, "auto-wonder-id"),
                        ],
                    }
                ),
                encoding="utf-8",
            )
            binary_dir = root / "bin"
            binary_dir.mkdir()
            log = root / "ossutil.log"
            self.write_executable(
                binary_dir / "aliyun",
                """#!/usr/bin/env bash
printf '{"AccountId":"1234567890123456"}\n'
""",
            )
            self.write_executable(
                binary_dir / "ossutil",
                """#!/usr/bin/env bash
case "${1:-}:${2:-}" in
  version:*|--version:*) echo 'ossutil version 2.1.0';;
  help:cp|help:rm) echo '--endpoint --region --force';;
  help:presign) echo '--expires-duration --endpoint --region';;
  *) printf '%s:%s\n' "${OSS_ACCESS_KEY_ID:-}" "$*" >> "$OSSUTIL_LOG";;
esac
""",
            )
            env = os.environ.copy()
            env.update(
                {
                    "PATH": f"{binary_dir}:{env['PATH']}",
                    "HOME": str(root),
                    "OSSUTIL_LOG": str(log),
                }
            )

            result = subprocess.run(
                [
                    "bash",
                    str(ROOT / "scripts/terraform-backend.sh"),
                    "destroy",
                    "--manifest",
                    str(manifest),
                    "--project-root",
                    str(root),
                ],
                text=True,
                capture_output=True,
                env=env,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            calls = [
                line
                for line in log.read_text(encoding="utf-8").splitlines()
                if not line.endswith(":help sign")
            ]
            self.assertTrue(calls)
            self.assertTrue(all(line.startswith("auto-wonder-id:") for line in calls), calls)

    def test_posix_bootstrap_revalidates_auto_wonder_profile_for_resume_workflow(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.write_manifest(root / "manifest.json")
            cli_dir = root / ".aliyun"
            cli_dir.mkdir()
            cli_config = cli_dir / "config.json"
            cli_config.write_text(
                json.dumps(
                    {
                        "current": "default",
                        "profiles": [self.profile(PROFILE, "expired-id")],
                    }
                ),
                encoding="utf-8",
            )
            refreshed_config = root / "refreshed-config.json"
            refreshed_config.write_text(
                json.dumps(
                    {"current": PROFILE, "profiles": [self.profile(PROFILE, "fresh-id")]}
                ),
                encoding="utf-8",
            )
            binary_dir = root / "bin"
            binary_dir.mkdir()
            log = root / "aliyun.log"
            auth_state = root / "oauth-complete"
            self.write_executable(binary_dir / "uname", "#!/usr/bin/env bash\necho Linux\n")
            self.write_executable(
                binary_dir / "aliyun",
                """#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "$ALIYUN_LOG"
if [[ " $* " == *" configure "* ]]; then
  cp "$REFRESHED_CONFIG" "$ALIBABA_CLOUD_CLI_CONFIG_FILE"
  : > "$AUTH_STATE"
  exit 0
fi
[[ -f "$AUTH_STATE" ]] || { echo "ErrorCode: InvalidSecurityToken.Expired" >&2; exit 41; }
printf '{"AccountId":"1234567890123456"}\n'
""",
            )
            env = os.environ.copy()
            env.update(
                {
                    "PATH": f"{binary_dir}:{env['PATH']}",
                    "HOME": str(root),
                    "ALIBABA_CLOUD_CLI_CONFIG_FILE": str(cli_config),
                    "ALIYUN_LOG": str(log),
                    "REFRESHED_CONFIG": str(refreshed_config),
                    "AUTH_STATE": str(auth_state),
                    "ALICLOUD_ACCESS_KEY": "ambient-id",
                    "ALICLOUD_SECRET_KEY": "ambient-secret",
                }
            )

            # Isolate tool setup from authentication: no downloads or real tools.
            scripts = root / 'scripts'
            scripts.mkdir()
            for name in ('bootstrap-control-host.sh', 'lib.sh', 'cloud_diagnostics.py'):
                shutil.copyfile(ROOT / 'scripts' / name, scripts / name)
            (scripts / 'runtime-env.sh').write_text(
                'autowonder_runtime_environment() { export AUTOWONDER_PYTHON="$FIXTURE_PYTHON" JAVA_HOME="$HOME/jdk" PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1; }\n')
            env['FIXTURE_PYTHON'] = sys.executable
            result = subprocess.run(
                [
                    "bash",
                    str(scripts / "bootstrap-control-host.sh"),
                    "--manifest",
                    str(manifest),
                ],
                text=True,
                capture_output=True,
                env=env,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            data = json.loads(result.stdout)
            self.assertEqual(data.pop('runtimeEnvironment')['AUTOWONDER_PYTHON'], sys.executable)
            self.assertEqual(
                {
                    "platform": "posix",
                    "profile": PROFILE,
                    "region": "cn-hangzhou",
                    "accountId": "1234567890123456",
                    "validated": True,
                },
                data,
            )
            calls = log.read_text(encoding="utf-8").splitlines()
            self.assertEqual(
                [
                    "sts GetCallerIdentity --region cn-hangzhou --profile auto-wonder",
                    "configure --profile auto-wonder --mode OAuth",
                    "sts GetCallerIdentity --region cn-hangzhou --profile auto-wonder",
                ],
                calls,
            )

    def test_windows_defaults_and_oauth_target_are_auto_wonder(self):
        bootstrap = (ROOT / "scripts/windows/bootstrap-control-host.ps1").read_text(
            encoding="utf-8"
        )
        cloud_assistant = (ROOT / "scripts/windows/cloud-assistant.ps1").read_text(
            encoding="utf-8"
        )
        windows_lib = (ROOT / "scripts/windows/lib.ps1").read_text(encoding="utf-8")

        self.assertIn("[string]$Profile = 'auto-wonder'", bootstrap)
        self.assertIn("configure --profile $Profile --mode OAuth", windows_lib)
        self.assertNotIn("'default'", bootstrap)
        self.assertIn("$profile = 'auto-wonder'", cloud_assistant)
        self.assertIn("Get-ObjectField $ManifestData 'cloudProfile'", cloud_assistant)
        self.assertIn("-ne $profile", cloud_assistant)
        self.assertNotIn("'default'", cloud_assistant)
        self.assertIn("Get-ChildItem Env:", windows_lib)
        self.assertIn("'ALICLOUD_*'", windows_lib)
        self.assertIn("'ALIBABA_CLOUD_ACCESS_KEY*'", windows_lib)

    def test_windows_bootstrap_reuses_shared_auto_wonder_profile_recovery(self):
        windows_lib = (ROOT / "scripts/windows/lib.ps1").read_text(encoding="utf-8")
        bootstrap = (ROOT / "scripts/windows/bootstrap-control-host.ps1").read_text(
            encoding="utf-8"
        )

        helper = windows_lib.split("function Ensure-AutoWonderAliyunProfile", 1)
        self.assertEqual(2, len(helper), "Windows library must expose the shared profile helper")
        helper_body = helper[1].split("\nfunction ", 1)[0]
        self.assertIn("$Profile = 'auto-wonder'", helper_body)
        self.assertGreaterEqual(helper_body.count("Import-AliyunCredential -Profile $Profile"), 2)
        self.assertGreaterEqual(helper_body.count("Assert-AliyunIdentity -Profile $Profile"), 2)
        self.assertIn("configure --profile $Profile --mode OAuth", helper_body)
        self.assertIn(
            "Ensure-AutoWonderAliyunProfile -Region $Region -ExpectedAccountId $ExpectedAccountId",
            bootstrap,
        )
        self.assertNotIn("configure --profile", bootstrap)

    def assert_oauth_recovery(self, initial_profiles):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.write_manifest(root / "manifest.json")
            cli_dir = root / ".aliyun"
            cli_dir.mkdir()
            cli_config = cli_dir / "config.json"
            cli_config.write_text(
                json.dumps({"current": "default", "profiles": initial_profiles}),
                encoding="utf-8",
            )
            refreshed_config = root / "refreshed-config.json"
            refreshed_config.write_text(
                json.dumps({"current": PROFILE, "profiles": [self.profile(PROFILE, "fresh-id")]}),
                encoding="utf-8",
            )
            binary_dir = root / "bin"
            binary_dir.mkdir()
            log = root / "aliyun.log"
            auth_state = root / "oauth-complete"
            self.write_executable(
                binary_dir / "aliyun",
                """#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "$ALIYUN_LOG"
if [[ " $* " == *" configure "* ]]; then
  cp "$REFRESHED_CONFIG" "$ALIBABA_CLOUD_CLI_CONFIG_FILE"
  : > "$AUTH_STATE"
  exit 0
fi
if [[ " $* " == *" sts GetCallerIdentity "* ]]; then
  [[ -f "$AUTH_STATE" ]] || { echo "ErrorCode: InvalidSecurityToken.Expired" >&2; exit 41; }
  printf '{"AccountId":"1234567890123456"}\n'
  exit 0
fi
exit 42
""",
            )
            env = os.environ.copy()
            env.update(
                {
                    "PATH": f"{binary_dir}:{env['PATH']}",
                    "HOME": str(root),
                    "ALIBABA_CLOUD_CLI_CONFIG_FILE": str(cli_config),
                    "ALIYUN_LOG": str(log),
                    "REFRESHED_CONFIG": str(refreshed_config),
                    "AUTH_STATE": str(auth_state),
                }
            )

            # Exercise shared authentication independently of resource discovery.
            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    'source "$1"; configure_cloud_profile "$2"; ensure_alicloud_profile_identity cn-hangzhou',
                    "bash",
                    str(ROOT / "scripts/lib.sh"),
                    str(manifest),
                ],
                text=True,
                capture_output=True,
                env=env,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            calls = log.read_text(encoding="utf-8").splitlines()
            oauth = "configure --profile auto-wonder --mode OAuth"
            identity = "sts GetCallerIdentity --region cn-hangzhou --profile auto-wonder"
            self.assertEqual(1, calls.count(oauth), calls)
            self.assertEqual(2, calls.count(identity), calls)
            self.assertLess(calls.index(identity), calls.index(oauth))
            self.assertLess(calls.index(oauth), len(calls) - 1 - calls[::-1].index(identity))
            self.assertTrue(all("--profile auto-wonder" in call for call in calls), calls)
            refreshed = json.loads(cli_config.read_text(encoding="utf-8"))
            self.assertEqual([PROFILE], [item["name"] for item in refreshed["profiles"]])

    @staticmethod
    def profile(name, access_key_id):
        return {
            "name": name,
            "access_key_id": access_key_id,
            "access_key_secret": f"{access_key_id}-secret",
            "sts_token": f"{access_key_id}-token",
        }

    @staticmethod
    def write_executable(path, content):
        path.write_text(content, encoding="utf-8")
        path.chmod(0o755)

    @classmethod
    def write_manifest(cls, path, include_profile=True):
        data = {
            "schemaVersion": 1,
            "mode": "resume",
            "phase": "questionnaire",
            "status": "planned",
            "region": "cn-hangzhou",
            "environment": "auto-wonder-prod",
            "deploymentId": "prod-abc12345",
            "accountUid": "1234567890123456",
            "topology": "multi-az-ha",
            "architecture": "x86_64",
            "availabilityZones": ["zone-a", "zone-b"],
            "sizePreset": "small",
            "stateMode": "remote",
            "lifecycle": "persistent",
            "executionMode": "unattended",
            "billing": {
                "strategy": "subscription-first",
                "purchasePeriodMonths": 1,
                "autoRenew": True,
                "autoRenewPeriodMonths": 1,
                "payAsYouGoExceptions": ["ALB", "OSS", "SLS"],
            },
            "ingressScenario": "no-domain-no-certificate",
            "domain": "",
            "publicSourceCidrs": ["198.51.100.0/24"],
            "network": {
                "vpcCidr": "10.0.0.0/16",
                "zoneACidr": "10.0.1.0/24",
                "zoneBCidr": "10.0.2.0/24",
            },
            "resolvedInfrastructure": {
                "ecsImageId": "aliyun-test-x86_64.vhd",
                "ecsInstanceType": "ecs.c8a.large",
                "preferredEcsInstanceType": "ecs.c8a.large",
                "ecsVcpus": 2,
                "ecsMemoryGiB": 4,
                "rdsInstanceType": "mysql.n2.medium.2c",
                "rdsCategory": "HighAvailability",
                "rdsStorageType": "cloud_essd",
                "rdsStorageGb": 100,
                "redisInstanceClass": "redis.shard.small.ce",
            },
            "slsEnabled": True,
            "aoneEnabled": False,
            "publicEgress": False,
            "adminUsername": "admin",
            "organizationName": "Example",
            "tags": {
                "Project": "AutoWonder",
                "Environment": "auto-wonder-prod",
                "DeploymentId": "prod-abc12345",
                "ManagedBy": "Terraform",
                "Topology": "multi-az-ha",
            },
            "terraform": {"mainDestroyVerified": False},
            "resources": {},
            "artifacts": {},
            "phases": [],
            "evidence": [],
        }
        if include_profile:
            data["cloudProfile"] = PROFILE
        path.write_text(json.dumps(data), encoding="utf-8")
        return path

    @staticmethod
    def write_source(path):
        resources = path / "src/main/resources"
        resources.mkdir(parents=True)
        (resources / "application.yml").write_text(
            "autowonder:\n  runtime:\n    recommended-version: "
            "${AUTOWONDER_RUNTIME_RECOMMENDED_VERSION:9.8.7}\n",
            encoding="utf-8",
        )
        return path


if __name__ == "__main__":
    unittest.main()
