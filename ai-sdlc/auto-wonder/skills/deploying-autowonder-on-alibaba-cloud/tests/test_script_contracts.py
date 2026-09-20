import json
import hashlib
import sys
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import shlex
import zipfile


ROOT = Path(__file__).resolve().parents[1]
UPGRADE_ROOT = ROOT.parent / "upgrading-autowonder-on-alibaba-cloud"
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
    def adaptive_fixture(self, manifest, binary, work):
        from unittest.mock import patch
        import test_resolve_zones as fixture
        import resolve_zones as resolver
        import resource_inventory as inventory
        data = json.loads(manifest.read_text())
        data['accountUid'] = '1234567890123456'
        data['region'] = 'cn-beijing'
        data['resources'] = {}
        data['resolvedInfrastructure'] = {'rdsCategory':'HighAvailability','rdsStorageGb':100}
        manifest.write_text(json.dumps(data))
        with patch.object(inventory, 'request', side_effect=fixture.cloud_response_adapter):
            self.assertEqual(resolver.resolve(manifest), 0)
        data = json.loads(manifest.read_text())
        plan = {'variables':{k:{'value':v} for k,v in resolver.expected_tfvars(data).items()}, 'resource_changes':[]}
        for kind, values in resolver.expected_resources(data).items():
            for idx, value in enumerate(values):
                if kind == 'alicloud_alb_load_balancer':
                    value['zone_mappings'] = [{'zone_id':z} for z in data['availabilityZones']]
                plan['resource_changes'].append({'address':kind+'.fixture'+str(idx),'type':kind,'mode':'managed',
                                                'change':{'actions':['create'],'before':None,'after':value}})
        fixture_plan = work / 'fixture-plan.json'
        fixture_plan.write_text(json.dumps(plan))
        fake = binary / 'aliyun'
        fake.write_text('#!'+sys.executable+'\nimport sys,json\nsys.path.insert(0,'+repr(str(Path(__file__).parent))+')\nfrom test_resolve_zones import cloud_response\na=sys.argv[1:]; p=dict(zip([k.removeprefix("--") for k in a[2::2]],a[3::2]))\nprint(json.dumps(cloud_response(a[0],a[1],p)))\n')
        fake.chmod(0o700)
        terraform = binary / 'terraform'
        content = terraform.read_text()
        # Preserve each test's command/path behavior, adding the real plan JSON boundary.
        line = 'if [[ "$*" == *"show -json"* ]]; then cat ' + shlex.quote(str(fixture_plan)) + '; exit 0; fi\n'
        content = content.replace('\n', '\n'+line, 1).replace('#!/bin/sh', '#!/usr/bin/env bash')
        terraform.write_text(content)

    REQUIRED_ENV = {
        "SPRING_DATASOURCE_URL": "jdbc:mysql://db.internal:3306/autowonder?useSSL=false",
        "SPRING_DATASOURCE_USERNAME": "autowonder",
        "SPRING_DATASOURCE_PASSWORD": "DbPassword1!",
        "REDIS_HOST": "redis.internal",
        "REDIS_PORT": "6379",
        "REDIS_PASSWORD": "RedisPassword1!",
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
                "current": "auto-wonder",
                "profiles": [{"name": "auto-wonder", "access_key_id": "test-id",
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

            scripts = root / 'isolated-scripts'
            scripts.mkdir()
            for name in ('terraform-backend.sh', 'lib.sh', 'cloud_diagnostics.py'):
                (scripts / name).write_bytes((ROOT / 'scripts' / name).read_bytes())
            (scripts / 'operations-store.py').write_text(
                'import json, pathlib, sys\n'
                'pathlib.Path(__file__).with_name("operations-call.json").write_text(json.dumps(sys.argv[1:]))\n'
            )
            result = subprocess.run([
                "bash", str(scripts / "terraform-backend.sh"), "prepare",
                "--manifest", str(manifest), "--project-root", str(root),
            ], text=True, capture_output=True, env=env)

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads((scripts / 'operations-call.json').read_text()),
                             ['initialize', '--manifest', str(manifest), '--project-root', str(root.resolve()), '--allow-incomplete'])
            self.assertEqual(json.loads(manifest.read_text())['localContext']['terraformDirectory'],
                             str(root.resolve() / 'deployments' / 'prod-abc12345' / 'terraform'))
            self.assertIn("api put-bucket-tags ", log.read_text())
            self.assertEqual("ready", json.loads(manifest.read_text())["terraform"]["backendStatus"])

    def test_ossutil_legacy_uses_cli_profile_via_protected_temporary_config(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            cli_config = root / "config.json"
            cli_config.write_text(json.dumps({
                "current": "auto-wonder",
                "profiles": [{"name": "auto-wonder", "access_key_id": "test-id",
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
        deploy = (ROOT / "scripts/internal/release-transfer.sh").read_text()
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
            'resolve_zones.py',
            'validate',
        ):
            self.assertIn(required, preflight)
        self.assertNotIn('--Cores 2 --Memory 4 --InstanceType', preflight)
        # The per-zone purchasable-stock probe now lives in the shared resolver,
        # which must query by instance type only and check real stock status.
        resolver = (ROOT / "scripts/resource_inventory.py").read_text()
        for required in (
            'DescribeAvailableResource',
            'InstanceChargeType',
            'PrePaid',
            'WithStock',
        ):
            self.assertIn(required, resolver)
        self.assertNotIn('--Cores', resolver)
        self.assertNotIn('--Memory', resolver)

    def test_preflight_dry_run_accepts_valid_manifest_and_rejects_secret(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            manifest_data = json.loads(manifest.read_text())
            manifest_data["mode"] = "resume"
            manifest.write_text(json.dumps(manifest_data))
            source = root / "source"
            (source / "src/main/resources").mkdir(parents=True)
            (source / "src/main/resources/application.yml").write_text(
                "autowonder:\n  runtime:\n    recommended-version: ${AUTOWONDER_RUNTIME_RECOMMENDED_VERSION:9.8.7}\n"
            )
            result = subprocess.run([
                "bash", str(ROOT / "scripts/preflight.sh"), "--manifest", str(manifest),
                "--source-dir", str(source), "--dry-run",
            ], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            data = json.loads(result.stdout)
            self.assertEqual(data["status"], "passed")
            self.assertEqual(
                "9.8.7",
                json.loads(manifest.read_text())["recommendedRuntimeVersion"],
            )
            raw = json.loads(manifest.read_text())
            raw["password"] = "TEST_SECRET_DO_NOT_PRINT"
            manifest.write_text(json.dumps(raw))
            result = subprocess.run([
                "bash", str(ROOT / "scripts/preflight.sh"), "--manifest", str(manifest),
                "--source-dir", str(source), "--dry-run",
            ], text=True, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("TEST_SECRET_DO_NOT_PRINT", result.stdout + result.stderr)

    def test_ecs_only_inventory_cannot_pass_full_preflight(self):
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
    has_type=false; has_cores=false; has_memory=false; zone=""; prev=""
    for arg in "$@"; do
      if [[ "$arg" == *$'\r'* ]]; then
        echo 'zone contains a carriage return' >&2
        exit 1
      fi
      [[ "$prev" == --ZoneId ]] && zone="$arg"
      [[ "$arg" == --InstanceType ]] && has_type=true
      [[ "$arg" == --Cores ]] && has_cores=true
      [[ "$arg" == --Memory ]] && has_memory=true
      prev="$arg"
    done
    if [[ "$has_type" == true && ("$has_cores" == true || "$has_memory" == true) ]]; then
      echo 'InvalidParam.TypeAndCpuMem.Conflict' >&2
      exit 1
    fi
    [[ -n "$zone" ]] || zone="zone-a"
    printf '{"AvailableZones":{"AvailableZone":[{"ZoneId":"%s","AvailableResources":{"AvailableResource":[{"SupportedResources":{"SupportedResource":[{"Value":"ecs.c8a.large","Status":"Available","StatusCategory":"WithStock"}]}}]}}]}}' "$zone"
    ;;
  *) exit 1;;
esac
''')
            aliyun.chmod(0o755)

            env = os.environ.copy()
            env["PATH"] = f"{binary_dir}:{env['PATH']}"
            env["ALIBABA_CLOUD_CLI_CONFIG_FILE"] = str(self.write_cloud_profile(root))
            result = subprocess.run([
                "bash", str(ROOT / "scripts/preflight.sh"),
                "--manifest", str(manifest), "--source-dir", str(ROOT.parents[1]),
            ], text=True, capture_output=True, env=env)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("resolved combination lacks complete current", result.stderr)

    def test_terraform_unresolved_state_blocks_every_stage_before_writes(self):
        stages = ('plan', 'apply', 'inventory', 'destroy-plan', 'destroy-apply')
        signals = ('pending-apply', 'pending-destroy', 'infrastructure-unknown',
                   'destroy-unknown', 'emergency-state', 'emergency-symlink')
        for stage in stages:
            for signal in signals:
                for bound in (False, True):
                    with self.subTest(stage=stage, signal=signal, bound=bound), tempfile.TemporaryDirectory() as td:
                        root = Path(td)
                        manifest = self.valid_manifest(root / 'manifest.json')
                        work = root / 'tf'
                        work.mkdir()
                        data = json.loads(manifest.read_text())
                        if bound:
                            data['operationsStore'] = {'revision': 'fixture'}
                        if signal.startswith('pending-'):
                            data['terraform']['pendingOperation'] = signal.removeprefix('pending-')
                        elif signal.endswith('-unknown'):
                            data.update(phase='infrastructure' if signal == 'infrastructure-unknown' else 'terraform-destroy', status='unknown')
                        elif signal == 'emergency-state':
                            (work / 'errored.tfstate').write_text('private emergency state')
                        else:
                            (work / 'errored.tfstate').symlink_to(work / 'missing-emergency-state')
                        fingerprint = hashlib.sha256(b'original plan').hexdigest()
                        data['terraform'].update(planFingerprint=fingerprint, destroyPlanFingerprint=fingerprint)
                        manifest.write_text(json.dumps(data))
                        protected = [manifest]
                        for name in ('reviewed.tfplan', 'destroy.tfplan', 'inventory.json', 'deployment.auto.tfvars.json'):
                            path = work / name
                            path.write_bytes(b'original plan')
                            protected.append(path)
                        before = {path: path.read_bytes() for path in protected}
                        binary = root / 'bin'
                        binary.mkdir()
                        calls = root / 'calls'
                        for name in ('terraform', 'python3'):
                            stub = binary / name
                            stub.write_text('#!/bin/sh\nprintf called >> "$FAKE_CALLS"\nexit 73\n')
                            stub.chmod(0o700)
                        result = subprocess.run(['bash', str(ROOT / 'scripts/terraform-stage.sh'), stage,
                            '--manifest', str(manifest), '--work-dir', str(work),
                            '--approved-plan-sha256', fingerprint], capture_output=True, text=True,
                            env=dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'],
                                     HOME=str(root), FAKE_CALLS=str(calls),
                                     AUTOWONDER_TERRAFORM_CONFIG_DIR=str(root / 'config')))
                        self.assertNotEqual(0, result.returncode)
                        self.assertEqual(before, {path: path.read_bytes() for path in protected})
                        self.assertFalse(calls.exists(), 'No Terraform or operations checkpoint may run')
                        self.assertFalse((work / 'terraform-secrets.env').exists())
                        self.assertFalse((root / 'config').exists())
                        self.assertIn('Terraform operation is unresolved', result.stderr)

    def test_terraform_guard_does_not_treat_teardown_preparation_as_unknown_apply(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / 'manifest.json')
            data = json.loads(manifest.read_text())
            data.update(teardownPreparation={'status': 'pending'}, phase='teardown-preparation', status='unknown')
            data['terraform']['pendingOperation'] = ''
            manifest.write_text(json.dumps(data))
            work = root / 'tf'
            work.mkdir()
            binary = root / 'bin'
            binary.mkdir()
            fake = binary / 'terraform'
            fake.write_text('#!/bin/sh\nfor arg in "$@"; do\n'
                            'case "$arg" in -out=*) printf reviewed-plan > "${arg#-out=}";; esac\ndone\n')
            fake.chmod(0o700)
            self.adaptive_fixture(manifest, binary, work)
            result = subprocess.run(['bash', str(ROOT / 'scripts/terraform-stage.sh'), 'plan',
                '--manifest', str(manifest), '--work-dir', str(work)], capture_output=True, text=True,
                env=dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'],
                         HOME=str(root), AUTOWONDER_TERRAFORM_CONFIG_DIR=str(root / 'config')))
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(b'reviewed-plan', (work / 'reviewed.tfplan').read_bytes())
            self.assertEqual({'status': 'pending'}, json.loads(manifest.read_text())['teardownPreparation'])

    def test_existing_update_requires_human_hash_before_terraform_submission(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / 'manifest.json')
            work, binary = root / 'tf', root / 'bin'
            work.mkdir(); binary.mkdir()
            applied = root / 'applied'
            terraform = binary / 'terraform'
            terraform.write_text('#!/usr/bin/env bash\nset -eu\n'
                'for arg in "$@"; do case "$arg" in -out=*) printf plan > "${arg#-out=}";; esac; done\n'
                'if [[ "$*" == *" apply "* ]]; then touch ' + shlex.quote(str(applied)) + '; fi\n')
            terraform.chmod(0o700)
            self.adaptive_fixture(manifest, binary, work)
            data = json.loads(manifest.read_text())
            data['mode'] = 'new'
            data['deployment'] = {'acceptedAt': '2026-01-01'}
            manifest.write_text(json.dumps(data))
            plan_path = work / 'fixture-plan.json'
            plan = json.loads(plan_path.read_text())
            plan['resource_changes'].append({'address': 'alicloud_alb_server_group.app',
                'type': 'alicloud_alb_server_group', 'mode': 'managed',
                'change': {'actions': ['update'], 'before': {'id': 'sg-a', 'servers': []},
                           'after': {'id': 'sg-a', 'servers': [{'server_id': 'i-a'}]}}})
            plan_path.write_text(json.dumps(plan))
            env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'],
                       AUTOWONDER_TERRAFORM_CONFIG_DIR=str(root / 'config'))
            script = ['bash', str(ROOT / 'scripts/terraform-stage.sh')]
            options = ['--manifest', str(manifest), '--work-dir', str(work)]
            result = subprocess.run(script + ['plan'] + options, env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
            self.assertIn('"updateConfirmationRequired": true', result.stdout)
            fingerprint = json.loads(manifest.read_text())['terraform']['planFingerprint']
            command = script + ['apply'] + options + ['--approved-plan-sha256', fingerprint]
            for extra in ([], ['--confirmed-update-plan-sha256', 'b' * 64]):
                result = subprocess.run(command + extra, env=env, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('update-confirmation-required', result.stdout)
                self.assertFalse(applied.exists())
                self.assertFalse(json.loads(manifest.read_text())['terraform'].get('pendingOperation'))
            result = subprocess.run(command + ['--confirmed-update-plan-sha256', fingerprint],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
            self.assertTrue(applied.exists())
            # The same in-place update is automatic during an unfinished new install.
            applied.unlink()
            data = json.loads(manifest.read_text())
            data.pop('deployment')
            data['terraform'].pop('existingDeployment')
            manifest.write_text(json.dumps(data))
            result = subprocess.run(command, env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr + result.stdout)
            self.assertTrue(applied.exists())

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

    def test_terraform_relative_workdir_plan_and_destroy_paths(self):
        with tempfile.TemporaryDirectory(prefix="terraform 路径 ") as td:
            root = Path(td).resolve()
            manifest = self.valid_manifest(root / "manifest.json")
            work = root / "relative 工作目录"
            work.mkdir()
            binary_dir = root / "bin"
            binary_dir.mkdir()
            fake = binary_dir / "terraform"
            fake.write_text("""#!/usr/bin/env bash
set -eu
cd -- "${1#-chdir=}"
shift
for arg in "$@"; do
  case "$arg" in -out=*) printf reviewed-plan > "${arg#-out=}";; esac
done
if [[ "$1" == apply ]]; then test -f "$2"; fi
""")
            fake.chmod(0o755)
            self.adaptive_fixture(manifest, binary_dir, work)
            env = dict(os.environ, PATH=str(binary_dir) + os.pathsep + os.environ["PATH"],
                       AUTOWONDER_TERRAFORM_CONFIG_DIR=str(root / "config"))
            script = str(ROOT / "scripts/terraform-stage.sh")
            confirmation = root / "confirmation"
            confirmation.write_text("DESTROY " + json.loads(manifest.read_text())["deploymentId"] + "\n")
            for command, filename, field in (("plan", "reviewed.tfplan", "planFingerprint"),
                                              ("destroy-plan", "destroy.tfplan", "destroyPlanFingerprint")):
                with self.subTest(command=command):
                    result = subprocess.run(["bash", script, command, "--manifest", str(manifest),
                        "--work-dir", work.name, "--confirmation-file", str(confirmation)],
                        cwd=root, env=env, capture_output=True, text=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual((work / filename).read_bytes(), b"reviewed-plan")
                    data = json.loads(manifest.read_text())
                    self.assertEqual(data["terraform"][field], hashlib.sha256(b"reviewed-plan").hexdigest())
                    if command == "plan":
                        self.assertEqual(data["terraform"]["planPath"], str(work / filename))
                        applied = subprocess.run(["bash", script, "apply", "--manifest", str(manifest),
                            "--work-dir", work.name, "--approved-plan-sha256", data["terraform"][field]],
                            cwd=root, env=env, capture_output=True, text=True)
                        self.assertEqual(applied.returncode, 0, applied.stderr)

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
            self.adaptive_fixture(manifest, binary_dir, work)
            env = os.environ.copy()
            env["PATH"] = f"{binary_dir}:{env['PATH']}"
            env["FAKE_TERRAFORM_LOG"] = str(log)
            script = str(ROOT / "scripts/terraform-stage.sh")
            plan = subprocess.run([
                script, "plan", "--manifest", str(manifest), "--work-dir", str(work),
            ], text=True, capture_output=True, env=env)
            self.assertEqual(plan.returncode, 0, plan.stderr)
            tfvars = json.loads((work / "deployment.auto.tfvars.json").read_text())
            self.assertEqual(tfvars["zone_a_id"], "cn-beijing-a")
            self.assertEqual(tfvars["zone_b_id"], "cn-beijing-b")
            self.assertEqual(tfvars["lifecycle_mode"], "persistent")
            self.assertEqual(tfvars["billing_strategy"], "subscription-first")
            self.assertEqual(tfvars["purchase_period_months"], 1)
            self.assertIs(tfvars["auto_renew"], True)
            self.assertEqual(tfvars["auto_renew_period_months"], 1)
            self.assertEqual(tfvars["ecs_image_id"], "aliyun_3_fixture")
            self.assertEqual(tfvars["vpc_cidr"], "10.0.0.0/16")
            self.assertNotIn("availability_zones", tfvars)
            calls = log.read_text().splitlines()
            self.assertEqual([c.split()[1] for c in calls[:4]], ["fmt", "init", "validate", "plan"])
            fingerprint = json.loads(manifest.read_text())["terraform"]["planFingerprint"]
            tfvars_path = work / "deployment.auto.tfvars.json"
            original_tfvars = tfvars_path.read_bytes()
            changed_vars = dict(tfvars, zone_a_cidr="10.0.9.0/24")
            tfvars_path.write_text(json.dumps(changed_vars))
            rejected = subprocess.run([
                script, "apply", "--manifest", str(manifest), "--work-dir", str(work),
                "--approved-plan-sha256", fingerprint,
            ], text=True, capture_output=True, env=env)
            self.assertNotEqual(rejected.returncode, 0)
            self.assertFalse(any(" apply " in line for line in log.read_text().splitlines()))
            self.assertFalse(json.loads(manifest.read_text())["terraform"].get("pendingOperation"))
            tfvars_path.write_bytes(original_tfvars)
            apply = subprocess.run([
                script, "apply", "--manifest", str(manifest), "--work-dir", str(work),
                "--approved-plan-sha256", fingerprint,
            ], text=True, capture_output=True, env=env)
            self.assertEqual(apply.returncode, 0, apply.stderr)
            self.assertTrue(log.read_text().splitlines()[-1].endswith("apply " + str(work.resolve() / "reviewed.tfplan")))

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
{"region":{"value":"cn-hangzhou"},"ecs_instance_ids":{"value":{"zone_a":"i-a","zone_b":"i-b"}},"load_balancer_id":{"value":"alb-test"},"load_balancer_address":{"value":"example.alb.aliyuncs.com"},"rds":{"value":{"connection":"db.internal","port":"3306","database":"autowonder","account":"autowonder"}},"redis":{"value":{"connection":"redis.internal","port":6379}},"oss":{"value":{"package_bucket":"pkg-example","artifact_bucket":"arti-example","control_endpoint":"oss-cn-hangzhou.aliyuncs.com","runtime_endpoint":"oss-cn-hangzhou-internal.aliyuncs.com"}},"sls":{"value":{"project":"logs-example","stores":{"system":"system","business":"business","metrics":"metrics"},"control_endpoint":"cn-hangzhou.log.aliyuncs.com","runtime_endpoint":"cn-hangzhou-intranet.log.aliyuncs.com"}}}
JSON
""")
            fake.chmod(0o755)
            aliyun = binary_dir / "aliyun"
            aliyun.write_text("#!/usr/bin/env python3\n"
                              "import os, sys\n"
                              "assert sys.argv[1:] == ['alb', 'GetLoadBalancerAttribute', '--region', 'cn-hangzhou', '--LoadBalancerId', 'alb-test', '--profile', 'auto-wonder']\n"
                              "print(os.environ['FAKE_ALB'])\n"
                              "sys.exit(int(os.environ.get('FAKE_ALB_EXIT', '0')))\n")
            aliyun.chmod(0o755)
            def alb_response(addresses):
                return json.dumps({"ZoneMappings": [
                    {"LoadBalancerAddresses": [{"Address": address}]} for address in addresses
                ]})
            env = os.environ.copy()
            env["FAKE_ALB"] = alb_response(["198.51.100.11", "198.51.100.2"])
            env["PATH"] = f"{binary_dir}:{env['PATH']}"
            command = ["bash", str(ROOT / "scripts/terraform-stage.sh"), "inventory",
                       "--manifest", str(manifest), "--work-dir", str(work)]
            result = subprocess.run(command, text=True, capture_output=True, env=env)
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

            self.assertEqual(resources["load_balancer_address"], "example.alb.aliyuncs.com")
            self.assertEqual(resources["alb_public_ipv4_addresses"], ["198.51.100.2", "198.51.100.11"])
            self.assertEqual(json.loads(manifest.read_text())["applicationBaseUrl"], "http://198.51.100.2")

            # API order must not change the selected default on a repeated inventory.
            env["FAKE_ALB"] = alb_response(["198.51.100.2", "198.51.100.11"])
            repeated = subprocess.run(command, text=True, capture_output=True, env=env)
            self.assertEqual(repeated.returncode, 0, repeated.stderr)
            self.assertEqual(json.loads(manifest.read_text())["applicationBaseUrl"], "http://198.51.100.2")

            # Never replace the known URL with a DNS fallback on failed/invalid inventory.
            before = manifest.read_text()
            for addresses, status in [([], 0), (["198.51.100.2"] * 2, 0),
                                      (["999.1.1.1", "198.51.100.2"], 0),
                                      (["example.alb.aliyuncs.com", "198.51.100.2"], 0),
                                      (["2001:db8::1", "2001:db8::2"], 0),
                                      (["198.51.100.2", "198.51.100.11"], 1)]:
                with self.subTest(addresses=addresses, api_status=status):
                    env["FAKE_ALB"] = alb_response(addresses)
                    env["FAKE_ALB_EXIT"] = str(status)
                    failed = subprocess.run(command, text=True, capture_output=True, env=env)
                    self.assertNotEqual(failed.returncode, 0)
                    self.assertEqual(manifest.read_text(), before)
            env["FAKE_ALB_EXIT"] = "0"

            # Carry the inventory result all the way into the generated application env.
            terraform_stub = fake.read_bytes()
            env_file = self.write_env(root / "autowonder.env")
            runtime = self.run_runtime_config(manifest, env_file)
            self.assertEqual(runtime.returncode, 0, runtime.stderr)
            values = dict(line.split("=", 1) for line in env_file.read_text().splitlines())
            self.assertEqual(shlex.split(values["AUTOWONDER_PUBLIC_BASE_URL"]), ["http://198.51.100.2"])

            # Restore the output stub replaced by the runtime helper, then test domain ingress.
            fake.write_bytes(terraform_stub)
            data = json.loads(manifest.read_text())
            data.update(ingressScenario="domain-no-certificate", domain="autowonder.example.com")
            manifest.write_text(json.dumps(data))
            domain = subprocess.run(command, text=True, capture_output=True, env=env)
            self.assertEqual(domain.returncode, 0, domain.stderr)
            self.assertEqual(json.loads(manifest.read_text())["applicationBaseUrl"], "http://autowonder.example.com")

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
            self.assertIn("automatic remote state backend is not ready", result.stderr)

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
            result = self.run_runtime_config(manifest, env_file)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("empty", result.stderr)

    def test_runtime_config_generates_strict_single_line_base64(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            env_file = self.write_env(root / "autowonder.env")
            result = self.run_runtime_config(manifest, env_file)
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
            data["applicationBaseUrl"] = "http://198.51.100.2"
            manifest.write_text(json.dumps(data))
            env_file = self.write_env(root / "autowonder.env")

            result = self.run_runtime_config(manifest, env_file)

            self.assertEqual(result.returncode, 0, result.stderr)
            values = {}
            for line in env_file.read_text().splitlines():
                key, value = line.split("=", 1)
                values[key] = shlex.split(value)[0] if value else ""
            self.assertEqual(
                "http://198.51.100.2",
                values["AUTOWONDER_PUBLIC_BASE_URL"],
            )

            explicit_env = self.write_env(
                root / "domain.env",
                {"AUTOWONDER_PUBLIC_BASE_URL": "https://autowonder.example.com"},
            )
            explicit = self.run_runtime_config(manifest, explicit_env)
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

            result = self.run_runtime_config(manifest, env_file)

            self.assertEqual(result.returncode, 0, result.stderr)
            version_lines = [
                line for line in env_file.read_text().splitlines()
                if line.startswith("AUTOWONDER_RUNTIME_RECOMMENDED_VERSION=")
            ]
            self.assertEqual(
                ["AUTOWONDER_RUNTIME_RECOMMENDED_VERSION=0.2.152"],
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

            result = self.run_runtime_config(manifest, env_file)

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

            result = self.run_runtime_config(manifest, env_file)

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

            result = self.run_runtime_config(manifest, env_file)

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("regional public HTTPS endpoint", result.stderr)

    def test_cloud_assistant_uses_current_cli_contract_without_double_encoding(self):
        for name in ("internal/release-transfer.sh", "internal/operations.sh"):
            text = (ROOT / "scripts" / name).read_text()
            self.assertIn('--CommandContent "$command_content"', text, name)
            self.assertIn("| base64 -d | /usr/bin/env bash", text, name)
            self.assertNotIn('--ContentEncoding', text, name)
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
        text = (ROOT / "scripts/internal/operations.sh").read_text()
        self.assertNotIn("/api/health", text)
        self.assertIn("/checkpreload.htm", text)
        self.assertIn("/api/platform/branding/public", text)
        self.assertIn("systemctl is-active --quiet autowonder.service", text)
        self.assertRegex(text, r"(?:ss|/proc/net/tcp).*7001")

    def test_transfer_separates_control_and_runtime_oss_endpoints(self):
        text = (ROOT / "scripts/internal/release-transfer.sh").read_text()
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
            env["ALIBABA_CLOUD_CLI_CONFIG_FILE"] = str(self.write_cloud_profile(root))
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
            key = {"AUTOWONDER_SECRET_MASTER_KEY": "c3ludGhldGljLW1hc3Rlci1rZXktMzItYnl0ZXMteHg="}
            env_file = self.write_env(root / "autowonder.env", key)
            active_env = self.write_env(root / "active.env", key)
            release = root / "release"
            release.mkdir()
            for name in (
                "auto-wonder.jar",
                "autowonder-schema.sql",
                "autowonder-community-templates.sql",
                "autowonder-migrations.tar.gz",
            ):
                (release / name).write_bytes(b"sealed")

            unit = release / "autowonder.service"
            # The sealed target can differ from both executing Skill bundles.
            unit.write_bytes((ROOT / "assets/systemd/autowonder.service").read_bytes()
                             + b"\n# sealed target release\n")
            data = json.loads(manifest.read_text())
            data.setdefault("localContext", {})["activeEnvFile"] = str(active_env)
            data["upgrade"] = {
                "blockedReasons": [], "environmentContractChecked": True,
                "environmentValidated": True, "targetRecommendedRuntimeVersion": "0.2.152",
                "environmentCandidateSha256": hashlib.sha256(env_file.read_bytes()).hexdigest(),
            }
            data["runtimeConfig"] = {
                "prepared": True, "recommendedRuntimeVersion": "0.2.152",
                "envSha256": data["upgrade"]["environmentCandidateSha256"],
            }
            data["artifacts"]["systemdUnit"] = {
                "sha256": hashlib.sha256(unit.read_bytes()).hexdigest(), "source": "target-source",
            }
            manifest.write_text(json.dumps(data))
            env = self.prepare_upgrade(manifest)
            data = json.loads(manifest.read_text())
            data["runtimeConfig"]["planFingerprint"] = data["upgrade"]["planFingerprint"]
            data["upgrade"]["rollbackBackup"] = {
                "status": "passed", "planFingerprint": data["upgrade"]["planFingerprint"],
                "fromCommit": data["upgrade"]["fromCommit"], "targetCommit": data["upgrade"]["toCommit"],
                "nodes": [{"instanceId": instance, "status": "passed", "sha256": "d" * 64}
                          for instance in data["resources"]["ecs_instance_ids"].values()],
            }
            manifest.write_text(json.dumps(data))
            result = subprocess.run([
                str(UPGRADE_ROOT / "scripts/stage-upgrade.sh"),
                "--manifest", str(manifest), "--env-file", str(env_file),
                "--release-dir", str(release), "--stage-only", "--dry-run",
            ], text=True, capture_output=True, env=env)

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("stage-only", json.loads(result.stdout)["mode"])

            command = [
                "bash", str(UPGRADE_ROOT / "scripts/stage-upgrade.sh"),
                "--manifest", str(manifest), "--env-file", str(env_file),
                "--release-dir", str(release), "--dry-run",
            ]
            explicit = subprocess.run(command + ["--unit-file", str(unit)],
                                      text=True, capture_output=True, env=env)
            self.assertEqual(0, explicit.returncode, explicit.stderr)
            wrong = subprocess.run(command + ["--unit-file", str(
                UPGRADE_ROOT / "assets/systemd/autowonder.service")],
                text=True, capture_output=True, env=env)
            self.assertNotEqual(0, wrong.returncode)
            self.assertIn("staged systemd unit does not match", wrong.stderr)
            unit.write_bytes(b"changed after sealing")
            changed = subprocess.run(command, text=True, capture_output=True, env=env)
            self.assertNotEqual(0, changed.returncode)
            self.assertIn("staged systemd unit does not match", changed.stderr)

    def test_upgrade_release_seals_migrations_and_preserves_active_symlink(self):
        build = (ROOT / "scripts/build-release.sh").read_text()
        deploy = (UPGRADE_ROOT / "scripts/internal/release-transfer.sh").read_text()
        normalized_deploy = deploy.replace('\\"', '"').replace('\\$', '$')

        self.assertIn("autowonder-migrations.tar.gz", build)
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
            (product / "src/main/resources").mkdir(parents=True)
            (product / "src/main/resources/application.yml").write_text(
                "autowonder:\n  runtime:\n    recommended-version: ${AUTOWONDER_RUNTIME_RECOMMENDED_VERSION:9.8.7}\n"
            )
            with zipfile.ZipFile(product / "target/auto-wonder.jar", "w") as jar:
                jar.write(product / "src/main/resources/application.yml", "BOOT-INF/classes/application.yml")
                jar.writestr("BOOT-INF/classes/static/index.html", "<html></html>")
                jar.writestr("BOOT-INF/classes/static/assets/index.js", "console.log('test');")
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
            env["AUTOWONDER_PYTHON"] = sys.executable

            result = subprocess.run(
                [
                    "bash",
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
            self.assertEqual(
                "9.8.7",
                json.loads(manifest.read_text())["recommendedRuntimeVersion"],
            )

    def test_preflight_allows_only_auto_wonder_credential_profile(self):
        text = (ROOT / "scripts/preflight.sh").read_text()
        self.assertIn("--profile PROFILE", text)
        self.assertIn('[[ -z "$profile" || "$profile" == auto-wonder ]]', text)
        self.assertIn("profile=auto-wonder", text)

    def test_verified_profile_is_reused_by_all_cloud_execution_scripts(self):
        for name in (
            "terraform-stage.sh",
            "internal/release-transfer.sh",
            "internal/operations.sh",
            "terraform-backend.sh",
        ):
            text = (ROOT / "scripts" / name).read_text()
            self.assertIn('configure_cloud_profile "$manifest"', text, name)
        for name in ("internal/release-transfer.sh", "internal/operations.sh"):
            text = (ROOT / "scripts" / name).read_text()
            self.assertIn("aliyun_cli ecs RunCommand", text, name)

    def test_cloud_assistant_polling_covers_remote_timeout(self):
        for name in ("internal/release-transfer.sh", "internal/operations.sh"):
            text = (ROOT / "scripts" / name).read_text()
            self.assertIn("--Timeout 1800", text, name)
            self.assertIn("deadline=$((SECONDS + 1860))", text, name)
            self.assertIn("while ((SECONDS < deadline))", text, name)
            self.assertIn('status="poll-timeout"', text, name)
            self.assertNotIn("attempts++ < 180", text, name)

    def test_database_parser_strips_jdbc_prefix_before_database_split(self):
        text = (ROOT / "scripts/internal/operations.sh").read_text()
        self.assertIn('connection=${SPRING_DATASOURCE_URL#jdbc:mysql://}', text)
        self.assertIn('authority=${connection%%/*}', text)
        self.assertIn('database=${connection#*/}', text)

    def record_current_upgrade_staging(self, manifest):
        data=json.loads(manifest.read_text())
        data["upgrade"].update(environmentCandidateSha256="c"*64,targetRecommendedRuntimeVersion="0.2.152")
        data["artifacts"]={"jar":{"sha256":"d"*64},"systemdUnit":{"sha256":"e"*64}}
        manifest.write_text(json.dumps(data))
        self.approve_upgrade(manifest)
        data=json.loads(manifest.read_text())
        plan=data["upgrade"]["planFingerprint"]
        data["runtimeConfig"]={"prepared":True,"recommendedRuntimeVersion":"0.2.152","envSha256":"c"*64,"planFingerprint":plan}
        data.setdefault("deployment",{})["lastRun"]={"mode":"stage-only","planFingerprint":plan,"targetCommit":data["upgrade"]["toCommit"],"jarSha256":"d"*64,"unitSha256":"e"*64,"envSha256":"c"*64,"instanceIds":list(data["resources"]["ecs_instance_ids"].values())}
        manifest.write_text(json.dumps(data))

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
            env = self.prepare_upgrade(manifest)
            self.record_current_upgrade_staging(manifest)
            command = [
                str(UPGRADE_ROOT / "scripts/upgrade-operations.sh"),
                "database-migrate", "--manifest", str(manifest),
            ]

            missing_confirmation = subprocess.run(command, text=True, capture_output=True, env=env)
            self.assertNotEqual(0, missing_confirmation.returncode)
            self.assertIn("explicit migration confirmation", missing_confirmation.stderr)

            missing_backup = subprocess.run(
                [*command, "--confirm-migrations"], text=True, capture_output=True, env=env
            )
            self.assertNotEqual(0, missing_backup.returncode)
            self.assertIn("recent live-verified database backup evidence for this RDS instance", missing_backup.stderr)

    def test_database_migration_requires_rolling_compatibility_decision(self):
        initialize = (UPGRADE_ROOT / "scripts/internal/operations.sh").read_text()
        self.assertIn("--confirm-rolling-compatible", initialize)
        self.assertIn("maintenance workflow is required", initialize)
        self.assertIn("databaseCompatibility.rollingAllowed", initialize)

    def test_database_migration_accepts_zero_padded_versions(self):
        initialize = (UPGRADE_ROOT / "scripts/internal/operations.sh").read_text()
        self.assertIn("V0*[1-9][0-9]*__", initialize)

    def test_upgrade_candidate_environment_is_hash_bound_before_staging(self):
        initialize = (UPGRADE_ROOT / "scripts/internal/operations.sh").read_text()
        deploy = (UPGRADE_ROOT / "scripts/internal/release-transfer.sh").read_text()
        self.assertIn(".upgrade.environmentCandidateSha256=$envHash", initialize)
        self.assertIn(".upgrade.environmentValidated=true", initialize)
        self.assertIn(".upgrade.environment.required[]", initialize)
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
            env = self.prepare_upgrade(manifest)
            self.record_current_upgrade_staging(manifest)

            result = subprocess.run([
                str(UPGRADE_ROOT / "scripts/upgrade-operations.sh"),
                "database-migrate", "--manifest", str(manifest),
            ], text=True, capture_output=True, env=env)

            self.assertEqual(0, result.returncode, result.stderr)
            migration = json.loads(manifest.read_text())["upgrade"]["databaseMigration"]
            self.assertEqual("not-required", migration["status"])

    def test_database_migration_has_lock_ledger_checksum_and_failure_fences(self):
        initialize = (UPGRADE_ROOT / "scripts/internal/operations.sh").read_text()
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
            env = self.prepare_upgrade(manifest)
            command = [
                str(UPGRADE_ROOT / "scripts/upgrade-operations.sh"),
                "rolling-upgrade", "--manifest", str(manifest),
            ]

            not_staged = subprocess.run(command, text=True, capture_output=True, env=env)
            self.assertNotEqual(0, not_staged.returncode)
            self.assertIn("verified staging must match current plan", not_staged.stderr)

            self.record_current_upgrade_staging(manifest)
            no_migration_checkpoint = subprocess.run(command, text=True, capture_output=True, env=env)
            self.assertNotEqual(0, no_migration_checkpoint.returncode)
            self.assertIn("database migration checkpoint", no_migration_checkpoint.stderr)

    def test_rolling_upgrade_switches_restarts_and_probes_nodes_sequentially(self):
        initialize = (UPGRADE_ROOT / "scripts/internal/operations.sh").read_text()
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
        deploy = (ROOT / "scripts/internal/release-transfer.sh").read_text()
        initialize = (ROOT / "scripts/internal/operations.sh").read_text()

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

    def test_sanitizer_omits_protected_environment_fingerprints(self):
        with tempfile.TemporaryDirectory() as td:
            source = Path(td) / "evidence.json"
            output = Path(td) / "clean.json"
            source.write_text(json.dumps({
                "runtimeConfig": {"envSha256": "a" * 64,
                                  "lastEnvironmentSha256": "e" * 64,
                                  "environmentCandidateSha256": "f" * 64,
                                  "AUTOWONDER_SECRET_MASTER_KEY": "protected-key-placeholder"},
                "upgrade": {"environmentSha256": "b" * 64,
                            "environmentPlanSha256": "1a" * 32,
                            "runtimeEnvironment": {"candidateSha256": "c" * 64}},
                "release": {"sha256": "d" * 64},
            }))
            result = subprocess.run([
                "bash", str(ROOT / "scripts/sanitize-evidence.sh"),
                "--input", str(source), "--output", str(output),
            ], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            clean = output.read_text()
            for fingerprint in ("a" * 64, "b" * 64, "c" * 64, "e" * 64, "f" * 64,
                                "1a" * 32, "protected-key-placeholder"):
                self.assertNotIn(fingerprint, clean)
            self.assertEqual(json.loads(clean)["release"]["sha256"], "d" * 64)

            source.write_text("environmentPlanSha256=" + "1a" * 32 + "\nphase=ready\n")
            result = subprocess.run([
                "bash", str(ROOT / "scripts/sanitize-evidence.sh"),
                "--input", str(source), "--output", str(output),
            ], text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertNotIn("1a" * 32, output.read_text())
            self.assertIn("phase=ready", output.read_text())

    def test_acceptance_rerun_preserves_completed_deep_checks(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = self.valid_manifest(root / "manifest.json")
            data = json.loads(manifest.read_text())
            data["applicationBaseUrl"] = "http://example.invalid"
            data["resources"]["alb_public_ipv4_addresses"] = ["198.51.100.10", "198.51.100.11"]
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
    def write_cloud_profile(root):
        path = root / "aliyun-config.json"
        path.write_text(json.dumps({"profiles": [{
            "name": "auto-wonder", "access_key_id": "fixture-id",
            "access_key_secret": "fixture-secret", "sts_token": "fixture-token",
        }]}))
        path.chmod(0o600)
        return path

    def run_runtime_config(self, manifest, env_file):
        root = manifest.parent
        work = root / "tf"
        work.mkdir(exist_ok=True)
        binary_dir = root / "bin"
        binary_dir.mkdir(exist_ok=True)
        data = json.loads(manifest.read_text())
        (work / "expected-tags.json").write_text(json.dumps(data["tags"]))
        terraform = binary_dir / "terraform"
        terraform.write_text('''#!/usr/bin/env bash
set -eu
work=${1#-chdir=}; shift
case "$*" in
  'output -json expected_tags') cat "$work/expected-tags.json";;
  'output -raw application_access_key_id') printf 'terraform-app-id';;
  'output -raw application_access_key_secret') printf 'terraform-app-secret';;
  *) exit 1;;
esac
''')
        terraform.chmod(0o755)
        env = {**os.environ, "PATH": f"{binary_dir}:{os.environ['PATH']}",
               "ALIBABA_CLOUD_CLI_CONFIG_FILE": str(self.write_cloud_profile(root))}
        result = subprocess.run([
            str(ROOT / "scripts/initialize-and-verify.sh"), "runtime-config",
            "--manifest", str(manifest), "--env-file", str(env_file),
            "--terraform-dir", str(work),
        ], text=True, capture_output=True, env=env)
        if result.returncode == 0:
            values = dict(line.split("=", 1) for line in env_file.read_text().splitlines())
            for prefix in ("OSS", "SLS"):
                self.assertEqual("terraform-app-id", shlex.split(values[f"{prefix}_ACCESS_KEY_ID"])[0])
                self.assertEqual("terraform-app-secret", shlex.split(values[f"{prefix}_ACCESS_KEY_SECRET"])[0])
            self.assertNotIn("terraform-app-secret", result.stdout + result.stderr)
            self.assertEqual(0o600, env_file.stat().st_mode & 0o777)
        return result

    def prepare_upgrade(self, manifest):
        root = manifest.parent
        binary_dir = root / "bin"
        binary_dir.mkdir(exist_ok=True)
        data = json.loads(manifest.read_text())
        data["mode"] = "upgrade"
        data["cloudProfile"] = "auto-wonder"
        data["resources"]["vpc_id"] = "vpc-test"
        data.setdefault("upgrade", {}).update(fromCommit="b" * 40, toCommit=data["repositoryCommit"])
        manifest.write_text(json.dumps(data))
        instances = [{"InstanceId": instance, "VpcAttributes": {"VpcId": "vpc-test"},
                      "Tags": {"Tag": [{"TagKey": key, "TagValue": value}
                                        for key, value in data["tags"].items()]}}
                     for instance in data["resources"]["ecs_instance_ids"].values()]
        (root / "cloud-inventory.json").write_text(json.dumps({"Instances": {"Instance": instances}}))
        aliyun = binary_dir / "aliyun"
        aliyun.write_text('''#!/usr/bin/env bash
set -eu
case "$1:$2" in
  sts:GetCallerIdentity) printf '{"AccountId":"123456789"}\n';;
  ecs:DescribeInstances)
    shift 2
    while (($#)); do
      if [[ "$1" == --InstanceIds ]]; then
        jq --argjson ids "$2" '.Instances.Instance |= map(select(.InstanceId as $id | $ids | index($id)))' "$FAKE_CLOUD_INVENTORY"
        exit 0
      fi
      shift
    done
    cat "$FAKE_CLOUD_INVENTORY";;
  *) exit 1;;
esac
''')
        aliyun.chmod(0o755)
        env = {**os.environ, "PATH": f"{binary_dir}:{os.environ['PATH']}",
               "ALIBABA_CLOUD_CLI_CONFIG_FILE": str(self.write_cloud_profile(root)),
               "FAKE_CLOUD_INVENTORY": str(root / "cloud-inventory.json")}
        verified = subprocess.run([
            "bash", str(UPGRADE_ROOT / "scripts/verify-deployment-targets.sh"),
            "--manifest", str(manifest),
        ], text=True, capture_output=True, env=env)
        self.assertEqual(0, verified.returncode, verified.stderr)
        self.approve_upgrade(manifest)
        return env

    def approve_upgrade(self, manifest):
        fingerprint = subprocess.check_output([
            sys.executable, "-B", str(UPGRADE_ROOT / "scripts/upgrade_plan.py"),
            "fingerprint", "--manifest", str(manifest),
        ], text=True).strip()
        data = json.loads(manifest.read_text())
        data["upgrade"]["planFingerprint"] = fingerprint
        data["upgrade"]["approval"] = {"status": "approved", "planFingerprint": fingerprint}
        manifest.write_text(json.dumps(data))

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
            "recommendedRuntimeVersion": "0.2.152",
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
