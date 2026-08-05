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
    "terraform-stage.sh",
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
{"region":{"value":"cn-hangzhou"},"ecs_instance_ids":{"value":{"zone_a":"i-a","zone_b":"i-b"}},"nlb_dns_name":{"value":"example.nlb.aliyuncs.com"},"rds":{"value":{"connection":"db.internal","port":"3306","database":"autowonder","account":"autowonder"}},"redis":{"value":{"connection":"redis.internal","port":6379}},"oss":{"value":{"package_bucket":"pkg-example","artifact_bucket":"arti-example","control_endpoint":"oss-cn-hangzhou.aliyuncs.com","runtime_endpoint":"oss-cn-hangzhou-internal.aliyuncs.com"}},"sls":{"value":{"project":"logs-example","stores":{"system":"system","business":"business","metrics":"metrics"},"control_endpoint":"cn-hangzhou.log.aliyuncs.com","runtime_endpoint":"cn-hangzhou-intranet.log.aliyuncs.com"}}}
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
                ["AUTOWONDER_RUNTIME_RECOMMENDED_VERSION=0.2.115"],
                version_lines,
            )

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

    def test_database_parser_strips_jdbc_prefix_before_database_split(self):
        text = (ROOT / "scripts/initialize-and-verify.sh").read_text()
        self.assertIn('connection=${SPRING_DATASOURCE_URL#jdbc:mysql://}', text)
        self.assertIn('authority=${connection%%/*}', text)
        self.assertIn('database=${connection#*/}', text)

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
                                       "rdsInstanceType": "mysql.n2.medium.2c", "rdsCategory": "HighAvailability",
                                       "rdsStorageType": "cloud_essd", "rdsStorageGb": 100,
                                       "redisInstanceClass": "redis.shard.small.ce"},
            "stateMode": "local", "lifecycle": "persistent", "executionMode": "staged",
            "ingressScenario": "no-domain-no-certificate", "domain": "", "publicSourceCidrs": ["198.51.100.0/24"],
            "applicationBaseUrl": "http://public-nlb.example.com",
            "recommendedRuntimeVersion": "0.2.115",
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
