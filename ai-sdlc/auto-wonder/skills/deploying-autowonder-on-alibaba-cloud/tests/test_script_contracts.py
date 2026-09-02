import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import shlex


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = [
    "preflight.sh",
    "plan-upgrade.sh",
    "terraform-stage.sh",
    "terraform-backend.sh",
    "build-release.sh",
    "deploy-via-cloud-assistant.sh",
    "initialize-and-verify.sh",
    "sanitize-evidence.sh",
]


class ScriptContracts(unittest.TestCase):
    REQUIRED_ENV = {
        "SPRING_DATASOURCE_URL": "jdbc:mysql://db.internal:3306/autowonder?useSSL=false",
        "SPRING_DATASOURCE_USERNAME": "autowonder",
        "SPRING_DATASOURCE_PASSWORD": "DbPassword1!",
        "REDIS_HOST": "redis.internal",
        "OSS_ENDPOINT": "https://oss-cn-hangzhou-internal.aliyuncs.com",
        "OSS_PUBLIC_ENDPOINT": "https://oss-cn-hangzhou.aliyuncs.com",
        "OSS_BUCKET": "artifact-example",
        "OSS_ACCESS_KEY_ID": "test-key-id",
        "OSS_ACCESS_KEY_SECRET": "test-key-secret",
        "SLS_ENDPOINT": "cn-hangzhou-intranet.log.aliyuncs.com",
        "SLS_PROJECT": "logs-example",
        "SLS_SYS_LOGSTORE": "system",
        "SLS_BIZ_LOGSTORE": "business",
        "SLS_METRIC_LOGSTORE": "metrics",
        "SLS_ACCESS_KEY_ID": "test-key-id",
        "SLS_ACCESS_KEY_SECRET": "test-key-secret",
        "AUTOWONDER_AONE_ENABLED": "false",
        "AUTOWONDER_SLS_ENABLED": "true",
        "AUTOWONDER_SIGAR_ENABLED": "true",
    }

    def test_backend_metadata_is_deterministic_and_uses_fixed_path(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({
                "region": "cn-hangzhou",
                "environment": "auto-wonder-prod",
                "deploymentId": "prod-abc12345",
                "accountUid": "1234567890123456",
                "stateMode": "remote",
                "terraform": {"backendStatus": "pending"},
            }))
            command = [
                "bash", str(ROOT / "scripts/terraform-backend.sh"), "metadata",
                "--manifest", str(manifest), "--project-root", str(root),
            ]
            first = subprocess.run(command, text=True, capture_output=True)
            self.assertEqual(0, first.returncode, first.stderr)
            first_data = json.loads(manifest.read_text())
            second = subprocess.run(command, text=True, capture_output=True)
            self.assertEqual(0, second.returncode, second.stderr)
            second_data = json.loads(manifest.read_text())
            self.assertEqual(first_data["terraform"], second_data["terraform"])
            self.assertRegex(first_data["terraform"]["stateBucket"], r"^aw-tfstate-prod-abc12345-[0-9a-f]{12}$")
            self.assertEqual("states/prod-abc12345/terraform.tfstate", first_data["terraform"]["stateKey"])
            self.assertEqual(
                str(root.resolve()).replace("\\", "/") + "/deployments/prod-abc12345/terraform/backend.hcl",
                first_data["terraform"]["stateReference"],
            )

            other = json.loads(manifest.read_text())
            other["deploymentId"] = "prod-def67890"
            other["terraform"] = {"backendStatus": "pending"}
            manifest.write_text(json.dumps(other))
            different = subprocess.run(command, text=True, capture_output=True)
            self.assertEqual(0, different.returncode, different.stderr)
            self.assertNotEqual(first_data["terraform"]["stateBucket"], json.loads(manifest.read_text())["terraform"]["stateBucket"])

    def test_backend_prepare_uses_ossutil_v2_bucket_tags_command(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({
                "schemaVersion": 1,
                "region": "cn-beijing",
                "environment": "auto-wonder-prod",
                "deploymentId": "prod-abc12345",
                "accountUid": "1234567890123456",
                "stateMode": "remote",
                "terraform": {"backendStatus": "pending"},
            }))
            binary_dir = root / "bin"
            binary_dir.mkdir()
            log = root / "ossutil.log"
            cli_dir = root / ".aliyun"
            cli_dir.mkdir()
            (cli_dir / "config.json").write_text(json.dumps({
                "current": "default",
                "profiles": [{"name": "default", "access_key_id": "test-id",
                              "access_key_secret": "test-secret", "sts_token": "test-token"}],
            }))

            aliyun = binary_dir / "aliyun"
            aliyun.write_text("#!/usr/bin/env bash\necho '{\"AccountId\":\"1234567890123456\"}'\n")
            aliyun.chmod(0o755)

            ossutil = binary_dir / "ossutil"
            ossutil.write_text(r'''#!/usr/bin/env bash
set -eu
printf '%s\n' "$*" >> "$OSSUTIL_LOG"
case "${1:-}:${2:-}" in
  version:*|--version:*) echo 'ossutil version 2.1.0';;
  help:cp|help:rm) echo '--endpoint --region --force';;
  help:presign) echo '--expires-duration --endpoint --region';;
  stat:*) exit 0;;
  api:put-bucket-acl|api:put-bucket-versioning|api:put-bucket-tags) exit 0;;
  api:put-bucket-tagging) echo 'unknown command' >&2; exit 1;;
  *) exit 1;;
esac
''')
            ossutil.chmod(0o755)
            env = os.environ.copy()
            env["PATH"] = f"{binary_dir}:{env['PATH']}"
            env["OSSUTIL_LOG"] = str(log)
            env["HOME"] = str(root)

            result = subprocess.run([
                "bash", str(ROOT / "scripts/terraform-backend.sh"), "prepare",
                "--manifest", str(manifest), "--project-root", str(root),
            ], text=True, capture_output=True, env=env)

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("api put-bucket-tags ", log.read_text())
            self.assertEqual("ready", json.loads(manifest.read_text())["terraform"]["backendStatus"])

    def test_ossutil_legacy_uses_cli_profile_via_protected_temporary_config(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            cli_config = root / "config.json"
            cli_config.write_text(json.dumps({
                "current": "deploy",
                "profiles": [{"name": "deploy", "access_key_id": "test-id",
                              "access_key_secret": "TEST_SECRET", "sts_token": "TEST_TOKEN"}],
            }))
            fake = root / "ossutil"
            observed = root / "observed.json"
            fake.write_text(r'''#!/usr/bin/env bash
set -eu
config=${2:-}
python3 - "$config" "$OSSUTIL_OBSERVED" <<'PY'
import json, os, pathlib, stat, sys
p = pathlib.Path(sys.argv[1])
text = p.read_text()
pathlib.Path(sys.argv[2]).write_text(json.dumps({
    "mode": stat.S_IMODE(p.stat().st_mode),
    "has_id": "accessKeyID=test-id" in text,
    "has_secret": "accessKeySecret=TEST_SECRET" in text,
    "has_token": "stsToken=TEST_TOKEN" in text,
    "path": str(p),
}))
PY
''')
            fake.chmod(0o755)
            env = os.environ.copy()
            env.update({
                "ALIBABA_CLOUD_CLI_CONFIG_FILE": str(cli_config),
                "OSSUTIL_OBSERVED": str(observed),
                "OSSUTIL_BIN": str(fake),
                "OSSUTIL_CONTRACT": "legacy",
            })
            result = subprocess.run([
                "bash", "-c", 'source "$1"; ossutil_cli stat oss://example',
                "bash", str(ROOT / "scripts/lib.sh"),
            ], text=True, capture_output=True, env=env)
            self.assertEqual(0, result.returncode, result.stderr)
            data = json.loads(observed.read_text())
            self.assertEqual(0o600, data["mode"])
            self.assertTrue(data["has_id"] and data["has_secret"] and data["has_token"])
            self.assertFalse(Path(data["path"]).exists())
            self.assertNotIn("TEST_SECRET", result.stdout + result.stderr)
            self.assertNotIn("TEST_TOKEN", result.stdout + result.stderr)

    def test_backend_destroy_requires_verified_main_destroy(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({
                "region": "cn-hangzhou", "environment": "auto-wonder-prod",
                "deploymentId": "prod-abc12345", "accountUid": "1234567890123456",
                "stateMode": "remote", "terraform": {"mainDestroyVerified": False},
            }))
            result = subprocess.run([
                "bash", str(ROOT / "scripts/terraform-backend.sh"), "destroy",
                "--manifest", str(manifest), "--project-root", str(root),
            ], text=True, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("main Terraform destroy is not verified", result.stderr)

    def test_scripts_have_safe_shell_contract(self):
        for name in SCRIPTS:
            path = ROOT / "scripts" / name
            self.assertTrue(path.is_file(), name)
            text = path.read_text()
            self.assertTrue(text.startswith("#!/usr/bin/env bash\nset -euo pipefail\n"), name)
            for forbidden in ("set -x", "apply -auto-approve", " ssh ", "\nssh ", "terraform destroy"):
                self.assertNotIn(forbidden, text, name)
            subprocess.run(["bash", "-n", str(path)], check=True)
            result = subprocess.run([str(path), "--help"], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, (name, result.stderr))

    def test_systemd_unit_is_non_root_and_secret_free(self):
        text = (ROOT / "assets/systemd/autowonder.service").read_text()
        for required in (
            "User=autowonder", "Group=autowonder",
            "EnvironmentFile=/etc/autowonder/autowonder.env",
            "ExecStart=/opt/autowonder/runtime/bin/java -jar /opt/autowonder/current/auto-wonder.jar",
            "Restart=on-failure",
        ):
            self.assertIn(required, text)
        self.assertNotIn("User=root", text)
        deploy = (ROOT / "scripts/deploy-via-cloud-assistant.sh").read_text()
        self.assertIn(
            "mv /etc/autowonder/autowonder.env.tmp /etc/autowonder/autowonder.env",
            deploy,
        )

    def test_preflight_enforces_two_vcpu_four_gib_ecs_in_both_zones(self):
        preflight = (ROOT / "scripts/preflight.sh").read_text()
        for required in (
            '.resolvedInfrastructure.ecsVcpus == 2',
            '.resolvedInfrastructure.ecsMemoryGiB == 4',
            'DescribeInstanceTypes',
            '.CpuCoreCount == 2',
            '.MemorySize == 4',
            '.CpuArchitecture == "X86"',
            'DescribeAvailableResource',
            '--InstanceType "$ecs_instance_type"',
            'ecs instance type is unavailable in zone',
        ):
            self.assertIn(required, preflight)
        self.assertNotIn('--Cores 2 --Memory 4 --InstanceType', preflight)

    def test_preflight_dry_run_accepts_valid_manifest_and_rejects_secret(self):
        with tempfile.TemporaryDirectory() as td:
            manifest = self.valid_manifest(Path(td) / "manifest.json")
            result = subprocess.run([
                str(ROOT / "scripts/preflight.sh"), "--manifest", str(manifest),
                "--source-dir", str(ROOT.parents[1]), "--dry-run",
            ], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            data = json.loads(result.stdout)
            self.assertEqual(data["status"], "passed")
            raw = json.loads(manifest.read_text())
            raw["password"] = "TEST_SECRET_DO_NOT_PRINT"
            manifest.write_text(json.dumps(raw))
            result = subprocess.run([
                str(ROOT / "scripts/preflight.sh"), "--manifest", str(manifest),
                "--source-dir", str(ROOT.parents[1]), "--dry-run",
            ], text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("TEST_SECRET_DO_NOT_PRINT", result.stdout + result.stderr)

    def test_preflight_queries_ecs_stock_by_instance_type_without_cpu_memory_conflict(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            data = json.loads(manifest.read_text())
            data["mode"] = "resume"
            manifest.write_text(json.dumps(data))

            binary_dir = root / "bin"
            binary_dir.mkdir()
            for name in ("terraform", "openssl", "curl"):
                executable = binary_dir / name
                executable.write_text("#!/usr/bin/env bash\nexit 0\n")
                executable.chmod(0o755)

            ossutil = binary_dir / "ossutil"
            ossutil.write_text(r'''#!/usr/bin/env bash
set -eu
case "${1:-}:${2:-}" in
  version:*|--version:*) echo 'ossutil version 2.1.0';;
  help:cp|help:rm) echo '--endpoint --region --force';;
  help:presign) echo '--expires-duration --endpoint --region';;
  *) exit 1;;
esac
''')
            ossutil.chmod(0o755)

            aliyun = binary_dir / "aliyun"
            aliyun.write_text(r'''#!/usr/bin/env bash
set -eu
product=${1:-}; operation=${2:-}; shift 2
case "$product:$operation" in
  sts:GetCallerIdentity) echo '{"AccountId":"1234567890123456"}';;
  ecs:DescribeZones) echo '{"Zones":{"Zone":[]}}';;
  ecs:DescribeInstanceTypes)
    echo '{"InstanceTypes":{"InstanceType":[{"InstanceTypeId":"ecs.c8a.large","CpuCoreCount":2,"MemorySize":4,"CpuArchitecture":"X86"}]}}'
    ;;
  ecs:DescribeAvailableResource)
    has_type=false; has_cores=false; has_memory=false
    for arg in "$@"; do
      if [[ "$arg" == *$'\r'* ]]; then
        echo 'zone contains a carriage return' >&2
        exit 1
      fi
      [[ "$arg" == --InstanceType ]] && has_type=true
      [[ "$arg" == --Cores ]] && has_cores=true
      [[ "$arg" == --Memory ]] && has_memory=true
    done
    if [[ "$has_type" == true && ("$has_cores" == true || "$has_memory" == true) ]]; then
      echo 'InvalidParam.TypeAndCpuMem.Conflict' >&2
      exit 1
    fi
    echo '{"AvailableZones":{"AvailableZone":[{"AvailableResources":{"AvailableResource":[{"SupportedResources":{"SupportedResource":[{"Value":"ecs.c8a.large"}]}}]}}]}}'
    ;;
  *) exit 1;;
esac
''')
            aliyun.chmod(0o755)

            env = os.environ.copy()
            env["PATH"] = f"{binary_dir}:{env['PATH']}"
            result = subprocess.run([
                "bash", str(ROOT / "scripts/preflight.sh"),
                "--manifest", str(manifest), "--source-dir", str(ROOT.parents[1]),
            ], text=True, capture_output=True, env=env)

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual("passed", json.loads(result.stdout)["status"])

    def test_terraform_apply_requires_matching_plan_hash(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            work = root / "tf"
            work.mkdir()
            (work / "reviewed.tfplan").write_bytes(b"reviewed-plan")
            raw = json.loads(manifest.read_text())
            raw["terraform"]["planFingerprint"] = "bad"
            raw["terraform"]["planPath"] = str(work / "reviewed.tfplan")
            manifest.write_text(json.dumps(raw))
            result = subprocess.run([
                str(ROOT / "scripts/terraform-stage.sh"), "apply", "--manifest", str(manifest),
                "--work-dir", str(work), "--approved-plan-sha256", "wrong",
            ], text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)

    def test_terraform_plan_then_exact_reviewed_apply(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            work = root / "tf"
            work.mkdir()
            binary_dir = root / "bin"
            binary_dir.mkdir()
            log = root / "terraform.log"
            fake = binary_dir / "terraform"
            fake.write_text("""#!/usr/bin/env bash
set -eu
printf '%s\\n' "$*" >> "$FAKE_TERRAFORM_LOG"
for arg in "$@"; do
  case "$arg" in -out=*) printf reviewed-plan > "${arg#-out=}";; esac
done
if [[ "$*" == *'output -json'* ]]; then printf '{}\\n'; fi
""")
            fake.chmod(0o755)
            env = os.environ.copy()
            env["PATH"] = f"{binary_dir}:{env['PATH']}"
            env["FAKE_TERRAFORM_LOG"] = str(log)
            script = str(ROOT / "scripts/terraform-stage.sh")
            plan = subprocess.run([
                script, "plan", "--manifest", str(manifest), "--work-dir", str(work),
            ], text=True, capture_output=True, env=env)
            self.assertEqual(plan.returncode, 0, plan.stderr)
            tfvars = json.loads((work / "deployment.auto.tfvars.json").read_text())
            self.assertEqual(tfvars["zone_a_id"], "zone-a")
            self.assertEqual(tfvars["zone_b_id"], "zone-b")
            self.assertEqual(tfvars["lifecycle_mode"], "persistent")
            self.assertEqual(tfvars["billing_strategy"], "subscription-first")
            self.assertEqual(tfvars["purchase_period_months"], 1)
            self.assertIs(tfvars["auto_renew"], True)
            self.assertEqual(tfvars["auto_renew_period_months"], 1)
            self.assertEqual(tfvars["ecs_image_id"], "aliyun-test-x86_64.vhd")
            self.assertEqual(tfvars["vpc_cidr"], "10.0.0.0/16")
            self.assertNotIn("availability_zones", tfvars)
            calls = log.read_text().splitlines()
            self.assertEqual([c.split()[1] for c in calls[:4]], ["fmt", "init", "validate", "plan"])
            fingerprint = json.loads(manifest.read_text())["terraform"]["planFingerprint"]
            apply = subprocess.run([
                script, "apply", "--manifest", str(manifest), "--work-dir", str(work),
                "--approved-plan-sha256", fingerprint,
            ], text=True, capture_output=True, env=env)
            self.assertEqual(apply.returncode, 0, apply.stderr)
            self.assertTrue(log.read_text().splitlines()[-1].endswith("apply " + str(work / "reviewed.tfplan")))

    def test_terraform_inventory_normalizes_actual_outputs(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            work = root / "tf"
            work.mkdir()
            binary_dir = root / "bin"
            binary_dir.mkdir()
            fake = binary_dir / "terraform"
            fake.write_text("""#!/usr/bin/env bash
set -eu
cat <<'JSON'
{"region":{"value":"cn-hangzhou"},"ecs_instance_ids":{"value":{"zone_a":"i-a","zone_b":"i-b"}},"load_balancer_address":{"value":"example.alb.aliyuncs.com"},"rds":{"value":{"connection":"db.internal","port":"3306","database":"autowonder","account":"autowonder"}},"redis":{"value":{"connection":"redis.internal","port":6379}},"oss":{"value":{"package_bucket":"pkg-example","artifact_bucket":"arti-example","control_endpoint":"oss-cn-hangzhou.aliyuncs.com","runtime_endpoint":"oss-cn-hangzhou-internal.aliyuncs.com"}},"sls":{"value":{"project":"logs-example","stores":{"system":"system","business":"business","metrics":"metrics"},"control_endpoint":"cn-hangzhou.log.aliyuncs.com","runtime_endpoint":"cn-hangzhou-intranet.log.aliyuncs.com"}}}
JSON
""")
            fake.chmod(0o755)
            env = os.environ.copy()
            env["PATH"] = f"{binary_dir}:{env['PATH']}"
            result = subprocess.run([
                str(ROOT / "scripts/terraform-stage.sh"), "inventory",
                "--manifest", str(manifest), "--work-dir", str(work),
            ], text=True, capture_output=True, env=env)
            self.assertEqual(result.returncode, 0, result.stderr)
            resources = json.loads(manifest.read_text())["resources"]
            self.assertEqual(resources["package_bucket"], "pkg-example")
            self.assertEqual(resources["ecs_instance_ids"], {"zone_a": "i-a", "zone_b": "i-b"})
            self.assertEqual(resources["rds"]["connection"], "db.internal")
            self.assertEqual(resources["sls"]["stores"]["metrics"], "metrics")
            self.assertEqual(resources["oss_endpoint"], "oss-cn-hangzhou-internal.aliyuncs.com")
            self.assertEqual(resources["oss_public_endpoint"], "oss-cn-hangzhou.aliyuncs.com")
            self.assertEqual(resources["oss"]["runtime_endpoint"], "oss-cn-hangzhou-internal.aliyuncs.com")
            self.assertEqual(resources["sls"]["runtime_endpoint"], "cn-hangzhou-intranet.log.aliyuncs.com")

    def test_remote_state_requires_backend_reference(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            data = json.loads(manifest.read_text())
            data["stateMode"] = "remote"
            manifest.write_text(json.dumps(data))
            work = root / "tf"
            work.mkdir()
            result = subprocess.run([
                str(ROOT / "scripts/terraform-stage.sh"), "plan",
                "--manifest", str(manifest), "--work-dir", str(work),
            ], text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("remote state backend reference", result.stderr)

    def test_manifest_guard_allows_security_status_metadata(self):
        with tempfile.TemporaryDirectory() as td:
            manifest = Path(td) / "manifest.json"
            manifest.write_text(json.dumps({
                "runtimeConfig": {"secretFileMode": "0600"},
                "acceptance": {"secretLogScan": "passed"},
            }))
            result = subprocess.run([
                "bash", "-c", 'source "$1"; reject_secret_keys "$2"', "bash",
                str(ROOT / "scripts/lib.sh"), str(manifest),
            ], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)

            manifest.write_text(json.dumps({"password": "must-not-be-stored"}))
            result = subprocess.run([
                "bash", "-c", 'source "$1"; reject_secret_keys "$2"', "bash",
                str(ROOT / "scripts/lib.sh"), str(manifest),
            ], text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("must-not-be-stored", result.stderr)

    def test_runtime_config_rejects_empty_decoded_required_value(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            env_file = self.write_env(root / "autowonder.env", {"SPRING_DATASOURCE_PASSWORD": ""})
            result = subprocess.run([
                str(ROOT / "scripts/initialize-and-verify.sh"), "runtime-config",
                "--manifest", str(manifest), "--env-file", str(env_file),
            ], text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("empty", result.stderr)

    def test_runtime_config_generates_strict_single_line_base64(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            env_file = self.write_env(root / "autowonder.env")
            result = subprocess.run([
                str(ROOT / "scripts/initialize-and-verify.sh"), "runtime-config",
                "--manifest", str(manifest), "--env-file", str(env_file),
            ], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            values = {}
            for line in env_file.read_text().splitlines():
                key, value = line.split("=", 1)
                values[key] = shlex.split(value)[0] if value else ""
            master = values["AUTOWONDER_SECRET_MASTER_KEY"]
            self.assertRegex(master, r"^[A-Za-z0-9+/]{43}=$")
            self.assertEqual(32, len(__import__("base64").b64decode(master, validate=True)))
            self.assertNotIn("\n", values["AUTOWONDER_JWT_SECRET"])

    def test_runtime_config_materializes_public_base_url(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            data = json.loads(manifest.read_text())
            data["applicationBaseUrl"] = "http://public-nlb.example.com"
            manifest.write_text(json.dumps(data))
            env_file = self.write_env(root / "autowonder.env")

            result = subprocess.run([
                str(ROOT / "scripts/initialize-and-verify.sh"), "runtime-config",
                "--manifest", str(manifest), "--env-file", str(env_file),
            ], text=True, capture_output=True)

            self.assertEqual(result.returncode, 0, result.stderr)
            values = {}
            for line in env_file.read_text().splitlines():
                key, value = line.split("=", 1)
                values[key] = shlex.split(value)[0] if value else ""
            self.assertEqual(
                "http://public-nlb.example.com",
                values["AUTOWONDER_PUBLIC_BASE_URL"],
            )

            explicit_env = self.write_env(
                root / "domain.env",
                {"AUTOWONDER_PUBLIC_BASE_URL": "https://autowonder.example.com"},
            )
            explicit = subprocess.run([
                str(ROOT / "scripts/initialize-and-verify.sh"), "runtime-config",
                "--manifest", str(manifest), "--env-file", str(explicit_env),
            ], text=True, capture_output=True)
            self.assertEqual(explicit.returncode, 0, explicit.stderr)
            self.assertIn(
                "AUTOWONDER_PUBLIC_BASE_URL=https://autowonder.example.com\n",
                explicit_env.read_text(),
            )

    def test_runtime_config_replaces_stale_recommended_runtime_version(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            env_file = self.write_env(
                root / "autowonder.env",
                {"AUTOWONDER_RUNTIME_RECOMMENDED_VERSION": "0.2.110"},
            )

            result = subprocess.run([
                str(ROOT / "scripts/initialize-and-verify.sh"), "runtime-config",
                "--manifest", str(manifest), "--env-file", str(env_file),
            ], text=True, capture_output=True)

            self.assertEqual(result.returncode, 0, result.stderr)
            version_lines = [
                line for line in env_file.read_text().splitlines()
                if line.startswith("AUTOWONDER_RUNTIME_RECOMMENDED_VERSION=")
            ]
            self.assertEqual(
                ["AUTOWONDER_RUNTIME_RECOMMENDED_VERSION=0.2.150"],
                version_lines,
            )

    def test_runtime_config_replaces_stale_application_version(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            data = json.loads(manifest.read_text())
            data["releaseVersion"] = "0.4.0"
            manifest.write_text(json.dumps(data))
            env_file = self.write_env(
                root / "autowonder.env",
                {"AUTOWONDER_VERSION": "0.3.5"},
            )

            result = subprocess.run([
                str(ROOT / "scripts/initialize-and-verify.sh"), "runtime-config",
                "--manifest", str(manifest), "--env-file", str(env_file),
            ], text=True, capture_output=True)

            self.assertEqual(result.returncode, 0, result.stderr)
            version_lines = [
                line for line in env_file.read_text().splitlines()
                if line.startswith("AUTOWONDER_VERSION=")
            ]
            self.assertEqual(["AUTOWONDER_VERSION=0.4.0"], version_lines)

    def test_runtime_config_rejects_public_service_oss_endpoint(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            env_file = self.write_env(
                root / "autowonder.env",
                {"OSS_ENDPOINT": "https://oss-cn-hangzhou.aliyuncs.com"},
            )

            result = subprocess.run([
                str(ROOT / "scripts/initialize-and-verify.sh"), "runtime-config",
                "--manifest", str(manifest), "--env-file", str(env_file),
            ], text=True, capture_output=True)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("regional intranet endpoint", result.stderr)

    def test_runtime_config_rejects_internal_public_oss_endpoint(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            env_file = self.write_env(
                root / "autowonder.env",
                {"OSS_PUBLIC_ENDPOINT": "https://oss-cn-hangzhou-internal.aliyuncs.com"},
            )

            result = subprocess.run([
                str(ROOT / "scripts/initialize-and-verify.sh"), "runtime-config",
                "--manifest", str(manifest), "--env-file", str(env_file),
            ], text=True, capture_output=True)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("regional public HTTPS endpoint", result.stderr)

    def test_cloud_assistant_uses_current_cli_contract_without_double_encoding(self):
        for name in ("deploy-via-cloud-assistant.sh", "initialize-and-verify.sh"):
            text = (ROOT / "scripts" / name).read_text()
            self.assertIn("--CommandContent \"$script\"", text, name)
            self.assertIn("cloud_assistant_invocation_id", text, name)
            self.assertIn("--InvokeId \"$invocation\"", text, name)
            self.assertIn("cloud_assistant_status", text, name)
            self.assertIn("cloud_assistant_exit_code", text, name)
            self.assertNotIn("CommandContent \"$encoded\"", text, name)
            self.assertLess(
                text.index('status:"submitted"'),
                text.index("DescribeInvocationResults"),
                name,
            )

    def test_cloud_assistant_parsers_accept_current_and_legacy_fields(self):
        script = r'''
source "$1"
current='{"InvokeId":"t-current","Invocation":{"InvocationResults":{"InvocationResult":[{"InvokeRecordStatus":"Finished","ExitCode":0}]}}}'
legacy='{"InvocationId":"t-legacy","Invocation":{"InvocationResults":{"InvocationResult":[{"InvocationStatus":"Success","ExitCode":"0"}]}}}'
printf '%s|%s|%s\n' "$(cloud_assistant_invocation_id <<<"$current")" "$(cloud_assistant_status <<<"$current")" "$(cloud_assistant_exit_code <<<"$current")"
printf '%s|%s|%s\n' "$(cloud_assistant_invocation_id <<<"$legacy")" "$(cloud_assistant_status <<<"$legacy")" "$(cloud_assistant_exit_code <<<"$legacy")"
'''
        result = subprocess.run(
            ["bash", "-c", script, "bash", str(ROOT / "scripts/lib.sh")],
            text=True,
            capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            ["t-current|Finished|0", "t-legacy|Success|0"],
            result.stdout.splitlines(),
        )

    def test_readiness_uses_only_public_probes_and_host_postconditions(self):
        text = (ROOT / "scripts/initialize-and-verify.sh").read_text()
        self.assertNotIn("/api/health", text)
        self.assertIn("/checkpreload.htm", text)
        self.assertIn("/api/platform/branding/public", text)
        self.assertIn("systemctl is-active --quiet autowonder.service", text)
        self.assertRegex(text, r"(?:ss|/proc/net/tcp).*7001")

    def test_transfer_separates_control_and_runtime_oss_endpoints(self):
        text = (ROOT / "scripts/deploy-via-cloud-assistant.sh").read_text()
        self.assertIn('control_endpoint=', text)
        self.assertIn('runtime_endpoint=', text)
        self.assertIn('ossutil_upload ', text)
        self.assertIn('ossutil_presign ', text)
        self.assertIn('ossutil_remove ', text)
        self.assertIn('"$control_endpoint" "$region"', text)
        self.assertIn('"$runtime_endpoint" "$region"', text)

    def test_ossutil_compatibility_detects_v2_and_legacy_without_leaking_url(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            fake = root / "ossutil"
            fake.write_text(r'''#!/usr/bin/env bash
set -eu
if [[ ${1:-} == -c ]]; then shift 2; fi
case "${OSSUTIL_FAKE_MODE}:${1:-}:${2:-}" in
  v2:version:*|v2:--version:*) echo 'ossutil version 2.1.0';;
  legacy:version:*|legacy:--version:*) echo 'ossutil version 1.7.19';;
  v2:help:presign) echo 'presign --expires-duration --endpoint --region';;
  v2:help:cp) echo 'cp --endpoint --region --force';;
  v2:help:rm) echo 'rm --endpoint --region --force';;
  legacy:help:presign) exit 1;;
  legacy:help:sign) echo 'sign --timeout -e';;
  legacy:help:cp) echo 'cp --endpoint -f';;
  legacy:help:rm) echo 'rm --endpoint -f';;
  v2:presign:*) printf '%s\n' "$*" >> "$OSSUTIL_FAKE_LOG"; printf '%s\n' 'https://bucket.example/object?security-token=SECRET';;
  legacy:sign:*) printf '%s\n' "$*" >> "$OSSUTIL_FAKE_LOG"; printf '%s\n' 'https://bucket.example/object?security-token=SECRET';;
  *:cp:*|*:rm:*) printf '%s\n' "$*" >> "$OSSUTIL_FAKE_LOG";;
  *) exit 1;;
esac
''')
            fake.chmod(0o755)
            env = os.environ.copy()
            env["PATH"] = f"{root}:{env['PATH']}"
            for mode, expected in (("v2", "v2"), ("legacy", "legacy")):
                env["OSSUTIL_FAKE_MODE"] = mode
                log = root / f"{mode}.log"
                env["OSSUTIL_FAKE_LOG"] = str(log)
                script = r'''
source "$1"
ossutil_preflight cn-hangzhou
ossutil_upload /tmp/release.jar oss://bucket/object oss-cn-hangzhou.aliyuncs.com cn-hangzhou
ossutil_remove oss://bucket/object oss-cn-hangzhou.aliyuncs.com cn-hangzhou
ossutil_presign oss://bucket/object oss-cn-hangzhou-internal.aliyuncs.com cn-hangzhou
printf 'contract=%s url_ready=%s\n' "$OSSUTIL_CONTRACT" "${OSSUTIL_PRESIGNED_URL:+yes}"
'''
                result = subprocess.run(
                    ["bash", "-c", script, "bash", str(ROOT / "scripts/lib.sh")],
                    text=True,
                    capture_output=True,
                    env=env,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(f"contract={expected} url_ready=yes\n", result.stdout)
                self.assertNotIn("SECRET", result.stdout + result.stderr)
                calls = log.read_text().splitlines()
                if mode == "v2":
                    self.assertTrue(all("--region cn-hangzhou" in call for call in calls))
                    self.assertTrue(all("--endpoint" in call for call in calls))
                else:
                    self.assertIn("--endpoint", calls[0])
                    self.assertIn("--endpoint", calls[1])
                    self.assertIn(" -e oss-cn-hangzhou-internal.aliyuncs.com", calls[2])
                    self.assertNotIn("--endpoint", calls[2])

    def test_config_only_retry_does_not_require_release_artifacts(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            data = json.loads(manifest.read_text())
            data["resources"]["package_bucket"] = "packages-example"
            manifest.write_text(json.dumps(data))
            env_file = self.write_env(root / "autowonder.env")
            result = subprocess.run([
                str(ROOT / "scripts/deploy-via-cloud-assistant.sh"),
                "--manifest", str(manifest), "--env-file", str(env_file),
                "--config-only", "--dry-run",
            ], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual("config-only", json.loads(result.stdout)["mode"])

    def test_stage_only_upgrade_requires_release_but_does_not_activate(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            data = json.loads(manifest.read_text())
            data["resources"]["package_bucket"] = "packages-example"
            data["repositoryCommit"] = "a" * 40
            manifest.write_text(json.dumps(data))
            env_file = self.write_env(root / "autowonder.env")
            release = root / "release"
            release.mkdir()
            for name in (
                "auto-wonder.jar",
                "autowonder-schema.sql",
                "autowonder-community-templates.sql",
                "autowonder-migrations.tar.gz",
            ):
                (release / name).write_bytes(b"sealed")

            result = subprocess.run([
                str(ROOT / "scripts/deploy-via-cloud-assistant.sh"),
                "--manifest", str(manifest), "--env-file", str(env_file),
                "--release-dir", str(release), "--stage-only", "--dry-run",
            ], text=True, capture_output=True)

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("stage-only", json.loads(result.stdout)["mode"])

    def test_upgrade_release_seals_migrations_and_preserves_active_symlink(self):
        build = (ROOT / "scripts/build-release.sh").read_text()
        deploy = (ROOT / "scripts/deploy-via-cloud-assistant.sh").read_text()
        normalized_deploy = deploy.replace('\\"', '"').replace('\\$', '$')

        self.assertIn("autowonder-migrations.tar.gz", build)
        self.assertIn('LC_ALL=C tar -czf "$output_dir/autowonder-migrations.tar.gz"', build)
        self.assertIn('migrations:{name:"autowonder-migrations.tar.gz"', build)
        self.assertIn("autowonder-migrations.tar.gz", deploy)
        self.assertIn("migrations_hash", deploy)
        self.assertIn("autowonder.env.previous", deploy)
        self.assertIn("autowonder.service.previous", deploy)
        self.assertIn('! test -f "$previous_env"', normalized_deploy)
        self.assertIn('if [[ "$stage_only" == false ]]', deploy)
        self.assertIn("ln -sfn /opt/autowonder/releases/$short_commit", deploy)

    def test_build_release_accepts_monorepo_project_subdirectory(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            repository = root / "repository"
            product = repository / "ai-sdlc" / "auto-wonder"
            product.mkdir(parents=True)
            subprocess.run(["git", "init", str(repository)], check=True, capture_output=True)
            subprocess.run(
                ["git", "config", "user.email", "build-test@example.invalid"],
                cwd=repository,
                check=True,
                capture_output=True,
            )
            subprocess.run(
                ["git", "config", "user.name", "Build Test"],
                cwd=repository,
                check=True,
                capture_output=True,
            )

            (product / "target").mkdir()
            (product / "target/auto-wonder.jar").write_bytes(b"test-jar")
            (product / "VERSION").write_text("0.4.0\n")
            (product / "docs/migration").mkdir(parents=True)
            (product / "docs/autowonder-schema.sql").write_text("SELECT 1;\n")
            (product / "docs/autowonder-community-templates.sql").write_text("SELECT 1;\n")
            (product / "docs/migration/README.md").write_text("migration contract\n")
            subprocess.run(["git", "add", "."], cwd=repository, check=True, capture_output=True)
            subprocess.run(
                ["git", "commit", "-m", "monorepo project"],
                cwd=repository,
                check=True,
                capture_output=True,
            )
            commit = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=repository,
                text=True,
                capture_output=True,
                check=True,
            ).stdout.strip()

            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"repositoryCommit": commit}), encoding="utf-8")
            output = root / "release"
            binary_dir = root / "bin"
            binary_dir.mkdir()
            mvn_pwd = root / "mvn-pwd"
            fake_mvn = binary_dir / "mvn"
            fake_mvn.write_text('#!/usr/bin/env bash\npwd > "$FAKE_MVN_PWD"\n')
            fake_mvn.chmod(0o755)
            fake_jar = binary_dir / "jar"
            fake_jar.write_text(
                "#!/usr/bin/env bash\n"
                "printf '%s\\n' BOOT-INF/classes/static/index.html "
                "BOOT-INF/classes/static/assets/index.js\n"
            )
            fake_jar.chmod(0o755)
            env = os.environ.copy()
            env["PATH"] = f"{binary_dir}:{env['PATH']}"
            env["FAKE_MVN_PWD"] = str(mvn_pwd)

            result = subprocess.run(
                [
                    str(ROOT / "scripts/build-release.sh"),
                    "--manifest",
                    str(manifest),
                    "--source-dir",
                    str(product),
                    "--output-dir",
                    str(output),
                ],
                text=True,
                capture_output=True,
                env=env,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(product.resolve(), Path(mvn_pwd.read_text().strip()).resolve())
            self.assertTrue((output / "autowonder-migrations.tar.gz").is_file())
            self.assertEqual("0.4.0", json.loads(manifest.read_text())["releaseVersion"])

    def test_preflight_supports_explicit_credential_profile(self):
        text = (ROOT / "scripts/preflight.sh").read_text()
        self.assertIn("--profile PROFILE", text)
        self.assertIn('--profile "$profile"', text)

    def test_verified_profile_is_reused_by_all_cloud_execution_scripts(self):
        for name in (
            "terraform-stage.sh",
            "deploy-via-cloud-assistant.sh",
            "initialize-and-verify.sh",
        ):
            text = (ROOT / "scripts" / name).read_text()
            self.assertIn('configure_cloud_profile "$manifest"', text, name)
        for name in ("deploy-via-cloud-assistant.sh", "initialize-and-verify.sh"):
            text = (ROOT / "scripts" / name).read_text()
            self.assertIn("aliyun_cli ecs RunCommand", text, name)

    def test_cloud_assistant_polling_covers_remote_timeout(self):
        for name in ("deploy-via-cloud-assistant.sh", "initialize-and-verify.sh"):
            text = (ROOT / "scripts" / name).read_text()
            self.assertIn("--Timeout 1800", text, name)
            self.assertIn("deadline=$((SECONDS + 1860))", text, name)
            self.assertIn("while ((SECONDS < deadline))", text, name)
            self.assertIn('status="poll-timeout"', text, name)
            self.assertNotIn("attempts++ < 180", text, name)

    def test_database_parser_strips_jdbc_prefix_before_database_split(self):
        text = (ROOT / "scripts/initialize-and-verify.sh").read_text()
        self.assertIn('connection=${SPRING_DATASOURCE_URL#jdbc:mysql://}', text)
        self.assertIn('authority=${connection%%/*}', text)
        self.assertIn('database=${connection#*/}', text)

    def test_database_migration_requires_confirmation_and_verified_backup(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            data = json.loads(manifest.read_text())
            data["mode"] = "upgrade"
            data["repositoryCommit"] = "a" * 40
            data["upgrade"] = {
                "blockedReasons": [],
                "databaseBackup": {"status": "pending"},
                "pendingMigrations": [{
                    "version": 1,
                    "file": "docs/migration/V1__add_state.sql",
                    "sha256": "b" * 64,
                    "riskOperations": ["ALTER"],
                }],
            }
            manifest.write_text(json.dumps(data))
            command = [
                str(ROOT / "scripts/initialize-and-verify.sh"),
                "database-migrate", "--manifest", str(manifest),
            ]

            missing_confirmation = subprocess.run(command, text=True, capture_output=True)
            self.assertNotEqual(0, missing_confirmation.returncode)
            self.assertIn("explicit migration confirmation", missing_confirmation.stderr)

            missing_backup = subprocess.run(
                [*command, "--confirm-migrations"], text=True, capture_output=True
            )
            self.assertNotEqual(0, missing_backup.returncode)
            self.assertIn("verified database backup", missing_backup.stderr)

    def test_database_migration_requires_rolling_compatibility_decision(self):
        initialize = (ROOT / "scripts/initialize-and-verify.sh").read_text()
        self.assertIn("--confirm-rolling-compatible", initialize)
        self.assertIn("maintenance workflow is required", initialize)
        self.assertIn("databaseCompatibility.rollingAllowed", initialize)

    def test_database_migration_accepts_zero_padded_versions(self):
        initialize = (ROOT / "scripts/initialize-and-verify.sh").read_text()
        self.assertIn("V0*[1-9][0-9]*__", initialize)

    def test_upgrade_candidate_environment_is_hash_bound_before_staging(self):
        initialize = (ROOT / "scripts/initialize-and-verify.sh").read_text()
        deploy = (ROOT / "scripts/deploy-via-cloud-assistant.sh").read_text()
        self.assertIn(".upgrade.environmentCandidateSha256=$envHash", initialize)
        self.assertIn(".upgrade.environmentValidated=true", initialize)
        self.assertIn(".upgrade.environment.added[]", initialize)
        self.assertIn('require_nonempty_env "$env_file" "$key"', initialize)
        self.assertIn(".upgrade.environmentContractChecked", deploy)
        self.assertIn(".upgrade.environmentCandidateSha256", deploy)
        self.assertIn('== "$env_hash"', deploy)

    def test_database_migration_is_noop_when_plan_has_no_pending_files(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            data = json.loads(manifest.read_text())
            data["mode"] = "upgrade"
            data["repositoryCommit"] = "a" * 40
            data["upgrade"] = {
                "blockedReasons": [],
                "databaseBackup": {"status": "pending"},
                "pendingMigrations": [],
            }
            manifest.write_text(json.dumps(data))

            result = subprocess.run([
                str(ROOT / "scripts/initialize-and-verify.sh"),
                "database-migrate", "--manifest", str(manifest),
            ], text=True, capture_output=True)

            self.assertEqual(0, result.returncode, result.stderr)
            migration = json.loads(manifest.read_text())["upgrade"]["databaseMigration"]
            self.assertEqual("not-required", migration["status"])

    def test_database_migration_has_lock_ledger_checksum_and_failure_fences(self):
        initialize = (ROOT / "scripts/initialize-and-verify.sh").read_text()
        migration = initialize.split("  database-migrate)", 1)[1].split(
            "  rolling-start)", 1
        )[0]
        normalized = migration.replace('\\"', '"').replace('\\$', '$')

        for required in (
            "--confirm-migrations",
            "sort_by(.version)",
            "coproc MIGRATION_LOCK",
            "lock_in=${MIGRATION_LOCK[1]}",
            "lock_out=${MIGRATION_LOCK[0]}",
            "lock_pid=$MIGRATION_LOCK_PID",
            "GET_LOCK('autowonder-community-migration', 30)",
            "RELEASE_LOCK('autowonder-community-migration')",
            "CREATE TABLE IF NOT EXISTS autowonder_schema_history",
            "checksum",
            "source_commit",
            "previous failed migration record",
            "MIGRATIONS_APPLIED",
        ):
            self.assertIn(required, initialize if required == "--confirm-migrations" else normalized)
        self.assertNotIn(
            'lock_acquired=$(mysql -h "$host"',
            normalized,
        )
        self.assertNotIn("autowonder-schema.sql", migration)

    def test_rolling_upgrade_requires_staged_release_and_migration_checkpoint(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            data = json.loads(manifest.read_text())
            data["mode"] = "upgrade"
            data["repositoryCommit"] = "a" * 40
            data["upgrade"] = {"blockedReasons": [], "databaseMigration": {}}
            manifest.write_text(json.dumps(data))
            command = [
                str(ROOT / "scripts/initialize-and-verify.sh"),
                "rolling-upgrade", "--manifest", str(manifest),
            ]

            not_staged = subprocess.run(command, text=True, capture_output=True)
            self.assertNotEqual(0, not_staged.returncode)
            self.assertIn("stage-only release", not_staged.stderr)

            data["deployment"] = {"lastRun": {"mode": "stage-only"}}
            manifest.write_text(json.dumps(data))
            no_migration_checkpoint = subprocess.run(command, text=True, capture_output=True)
            self.assertNotEqual(0, no_migration_checkpoint.returncode)
            self.assertIn("database migration checkpoint", no_migration_checkpoint.stderr)

    def test_rolling_upgrade_switches_restarts_and_probes_nodes_sequentially(self):
        initialize = (ROOT / "scripts/initialize-and-verify.sh").read_text()
        rolling = initialize.split("  rolling-upgrade)", 1)[1].split(
            "  rolling-start)", 1
        )[0]
        normalized = rolling.replace('\\"', '"')

        for required in (
            'for instance in "${instances[@]}"',
            "ln -sfn /opt/autowonder/releases/$short_commit",
            "mv -Tf /opt/autowonder/current.new /opt/autowonder/current",
            "systemctl restart autowonder.service",
            "readlink -f /opt/autowonder/current",
            "sha256sum",
            "systemctl is-active --quiet autowonder.service",
            'ss -ltnH "sport = :7001"',
            "/checkpreload.htm",
            "/api/platform/branding/public",
            "expected target release is not staged",
            "ROLLING_STATUS=failed",
            "RESOLUTION_REQUIRED=human-confirmation",
        ):
            self.assertIn(required, normalized)
        self.assertNotIn("rollback_status=passed", normalized)
        self.assertNotIn("automatic application rollback", normalized)
        self.assertIn('.status=(if $status == "passed" then "running" else "failed" end)', rolling)
        self.assertLess(
            rolling.index('for instance in "${instances[@]}"'),
            rolling.index(".rollingUpgrade={status:\"passed\""),
        )

    def test_release_and_database_include_squad_template_seed(self):
        build = (ROOT / "scripts/build-release.sh").read_text()
        deploy = (ROOT / "scripts/deploy-via-cloud-assistant.sh").read_text()
        initialize = (ROOT / "scripts/initialize-and-verify.sh").read_text()

        seed_name = "autowonder-community-templates.sql"
        self.assertIn(seed_name, build)
        self.assertIn('templates:{name:"autowonder-community-templates.sql"', build)
        self.assertIn(seed_name, deploy)
        self.assertIn("templates_hash", deploy)
        self.assertIn(".database.templatesImported // false", initialize)
        self.assertIn(seed_name, initialize)
        self.assertIn("TEMPLATE_COUNT=", initialize)
        self.assertLess(
            initialize.index("autowonder-schema.sql"),
            initialize.index(seed_name),
        )

    def test_release_build_requires_frontend_assets_in_jar(self):
        build = (ROOT / "scripts/build-release.sh").read_text()

        self.assertIn("-DskipFrontend=false", build)
        self.assertNotIn("-DskipFrontend=true", build)
        self.assertIn("BOOT-INF/classes/static/index.html", build)
        self.assertIn("BOOT-INF/classes/static/assets/", build)

    def test_sanitizer_removes_sensitive_fields_and_identifiers(self):
        with tempfile.TemporaryDirectory() as td:
            source = Path(td) / "evidence.json"
            output = Path(td) / "clean.json"
            source.write_text(json.dumps({
                "phase": "application", "status": "passed", "sha256": "a" * 64,
                "password": "TEST_SECRET_DO_NOT_PRINT",
                "instanceId": "i-example1234567890",
                "invokeId": "t-example1234567890",
                "publicIp": "203.0.113.10",
                "message": "request token=executor-secret",
                "encryptedCredentialRestart": "passed",
            }))
            result = subprocess.run([
                str(ROOT / "scripts/sanitize-evidence.sh"), "--input", str(source),
                "--output", str(output),
            ], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            clean = output.read_text()
            self.assertIn('"phase"', clean)
            self.assertIn('"sha256"', clean)
            self.assertIn('"encryptedCredentialRestart"', clean)
            for sentinel in ("TEST_SECRET_DO_NOT_PRINT", "i-example", "t-example", "203.0.113.10", "executor-secret"):
                self.assertNotIn(sentinel, clean)

    def test_acceptance_rerun_preserves_completed_deep_checks(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            data = json.loads(manifest.read_text())
            data["applicationBaseUrl"] = "http://example.invalid"
            data["acceptance"] = {
                "databasePersistence": "passed",
                "secretLogScan": "passed",
                "runtimeWebSocket": "passed",
            }
            manifest.write_text(json.dumps(data))
            binary_dir = root / "bin"
            binary_dir.mkdir()
            curl = binary_dir / "curl"
            curl.write_text("""#!/usr/bin/env bash
case "$*" in
  *capabilities*) printf '{"aoneEnabled":false}\\n';;
  *) printf 'success\\n';;
esac
""")
            curl.chmod(0o755)
            env = os.environ.copy()
            env["PATH"] = f"{binary_dir}:{env['PATH']}"
            result = subprocess.run([
                str(ROOT / "scripts/initialize-and-verify.sh"), "acceptance",
                "--manifest", str(manifest),
            ], text=True, capture_output=True, env=env)
            self.assertEqual(result.returncode, 0, result.stderr)
            acceptance = json.loads(manifest.read_text())["acceptance"]
            self.assertEqual("passed", acceptance["databasePersistence"])
            self.assertEqual("passed", acceptance["secretLogScan"])
            self.assertEqual("passed", acceptance["runtimeWebSocket"])

    def test_handoff_confirmation_is_idempotent_after_file_removal(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            handoff = root / ".autowonder-admin-handoff.json"
            handoff.write_text('{"username":"admin","password":"temporary"}')
            handoff.chmod(0o600)
            command = [
                str(ROOT / "scripts/initialize-and-verify.sh"), "handoff",
                "--manifest", str(manifest), "--handoff-file", str(handoff),
                "--confirm-received",
            ]
            first = subprocess.run(command, text=True, capture_output=True)
            second = subprocess.run(command, text=True, capture_output=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            self.assertEqual(second.returncode, 0, second.stderr)
            self.assertFalse(handoff.exists())

    @staticmethod
    def valid_manifest(path):
        data = {
            "schemaVersion": 1, "mode": "new", "phase": "questionnaire", "status": "planned",
            "region": "cn-hangzhou", "environment": "test", "deploymentId": "aw-test",
            "topology": "multi-az-ha", "architecture": "x86_64", "availabilityZones": ["zone-a", "zone-b"],
            "network": {"vpcCidr": "10.0.0.0/16", "zoneACidr": "10.0.1.0/24", "zoneBCidr": "10.0.2.0/24"},
            "resolvedInfrastructure": {"ecsImageId": "aliyun-test-x86_64.vhd", "ecsInstanceType": "ecs.c8a.large",
                                       "preferredEcsInstanceType": "ecs.c8a.large", "ecsVcpus": 2,
                                       "ecsMemoryGiB": 4,
                                       "rdsInstanceType": "mysql.n2.medium.2c", "rdsCategory": "HighAvailability",
                                       "rdsStorageType": "cloud_essd", "rdsStorageGb": 100,
                                       "redisInstanceClass": "redis.shard.small.ce"},
            "stateMode": "local", "lifecycle": "persistent", "executionMode": "staged",
            "billing": {"strategy": "subscription-first", "purchasePeriodMonths": 1,
                        "autoRenew": True, "autoRenewPeriodMonths": 1,
                        "payAsYouGoExceptions": ["ALB", "OSS", "SLS"]},
            "ingressScenario": "no-domain-no-certificate", "domain": "", "publicSourceCidrs": ["198.51.100.0/24"],
            "applicationBaseUrl": "http://public-nlb.example.com",
            "releaseVersion": "0.4.0",
            "recommendedRuntimeVersion": "0.2.150",
            "slsEnabled": True, "aoneEnabled": False, "publicEgress": False,
            "adminUsername": "admin", "organizationName": "Example", "repositoryUrl": "local",
            "repositoryRef": "community", "repositoryCommit": "HEAD",
            "tags": {"Project": "AutoWonder", "Environment": "test", "DeploymentId": "aw-test",
                     "ManagedBy": "Terraform", "Topology": "multi-az-ha"},
            "terraform": {"stateReference": "", "planFingerprint": ""},
            "resources": {"ecs_instance_ids": {"zone_a": "i-a", "zone_b": "i-b"}},
            "artifacts": {}, "phases": [], "evidence": [],
        }
        path.write_text(json.dumps(data))
        return path

    @classmethod
    def write_env(cls, path, overrides=None):
        values = dict(cls.REQUIRED_ENV)
        values.update(overrides or {})
        path.write_text("".join(f"{key}={shlex.quote(value)}\n" for key, value in values.items()))
        path.chmod(0o600)
        return path


if __name__ == "__main__":
    unittest.main()
