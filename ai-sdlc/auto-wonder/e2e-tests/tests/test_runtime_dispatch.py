"""Safety regressions; these unit tests are not real runtime E2E evidence."""
import os
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch
import tempfile
import time
import signal
import hashlib
import shutil

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "e2e-tests/runtime_dispatch.py"
spec = importlib.util.spec_from_file_location("runtime_dispatch", HELPER)
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeSafetyTests(unittest.TestCase):
    def function(self, name):
        fn = getattr(runtime, name, None)
        self.assertTrue(callable(fn), f"missing safety contract: {name}")
        return fn

    def test_http_200_business_failure_is_rejected(self):
        fn = self.function("require_api_success")
        for body in ({"success": False}, {"success": "true"}, {}):
            with self.assertRaises(runtime.CheckFailure):
                fn(body)
        self.assertEqual(fn({"success": True, "data": 42}), 42)

    def test_step_payload_uses_valid_optional_json_columns(self):
        payload = self.function("step_request")("write the proof")
        for field in ("onSuccess", "onFail", "checklistJson", "gatePolicyJson"):
            value = payload.get(field)
            if value is not None:
                try:
                    json.loads(value)
                except ValueError:
                    self.fail(f"{field} must be valid JSON, not an enum string")
        self.assertEqual(payload["instructionMd"], "write the proof")

    def test_executor_selection_preserves_existing_host_lock(self):
        fn = self.function("reserve_executor")
        class FakeApi:
            created = 99
            deleted = []
            def call(self, method, path, body=None):
                if method == "POST":
                    self.created += 1
                    return {"id": self.created, "token": "unit-test-only"}
                self.deleted.append(path)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock = root / "executor-100.lock"
            lock.write_text("existing-runtime")
            api = FakeApi()
            result = fn(api, 1, root)
            self.assertEqual(result["id"], 101)
            self.assertEqual(api.deleted, ["/api/executors/100"])
            self.assertEqual(lock.read_text(), "existing-runtime")

    def test_artifact_requires_exact_content(self):
        fn = self.function("require_artifact")
        with self.assertRaises(runtime.CheckFailure):
            fn(b"wrong\n", b"expected\n")
        self.assertEqual(fn(b"hello", b"hello"),
                         "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")

    def test_dispatch_must_match_executor_version_step_and_task(self):
        fn = self.function("require_dispatch")
        expected = {"workitemId": 1, "agentId": 2, "agentVersionId": 3,
                    "executorId": 4, "sdlcStepId": 5}
        fn(dict(expected, status="SUCCEEDED"), expected)
        for field in expected:
            with self.assertRaises(runtime.CheckFailure):
                fn(dict(expected, status="SUCCEEDED", **{field: 999}), expected)
        with self.assertRaises(runtime.CheckFailure):
            fn(dict(expected, status="FAILED"), expected)

    def test_download_rejects_foreign_host_credentials_and_port(self):
        fn = self.function("validate_download")
        origin = "http://127.0.0.1:19000"
        fn(origin + "/bucket/file?signature=private", origin)
        for url in ("http://example.com/file", "file:///tmp/data",
                    "http://127.0.0.1:19001/file", "http://user:secret@127.0.0.1:19000/file"):
            with self.assertRaises(runtime.CheckFailure):
                fn(url, origin)

    def test_missing_or_wrong_dispatch_artifact_rejected(self):
        fn = self.function("select_artifact")
        for artifacts in ([], [{"id": 1, "name": "runtime-proof.txt", "dispatchId": 99}]):
            with self.assertRaises(runtime.CheckFailure):
                fn(artifacts, 4)
        self.assertEqual(fn([{"id": 1, "name": "runtime-proof.txt", "dispatchId": 4}], 4), 1)

    def test_server_transition_evidence_cannot_match_different_dispatch(self):
        fn = self.function("require_transitions")
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "app.log"
            log.write_text("dispatch transition dispatchId=123 status=ACKED version=1\n"
                           "dispatch transition dispatchId=123 status=RUNNING version=2\n")
            fn(log, 123)
            with self.assertRaises(runtime.CheckFailure):
                fn(log, 12)

    def test_owned_process_cleanup_does_not_kill_another_process(self):
        owned = self.function("OwnedRuntime")
        outsider = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        try:
            with tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(TimeoutError):
                    with owned([sys.executable, "-c", "import time; time.sleep(30)"],
                               {}, Path(directory)) as process:
                        pid = process.pid
                        raise TimeoutError()
                self.assertIsNotNone(process.poll())
                self.assertIsNone(outsider.poll())
                self.assertEqual(list(Path(directory).iterdir()), [])
        finally:
            outsider.terminate()
            outsider.wait(timeout=5)

    def test_cleanup_reaps_provider_in_separate_process_group(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "provider.pid"
            code = ("import subprocess,sys,time,signal; "
                    "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
                    "p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'],start_new_session=True); "
                    "open(sys.argv[1],'w').write(str(p.pid)); time.sleep(60)")
            provider_pid = None
            try:
                with runtime.OwnedRuntime([sys.executable, "-c", code, str(marker)], {}, Path(directory)):
                    runtime.wait_for(marker.exists, 5, "TEST_PROCESS_START_TIMEOUT")
                    provider_pid = int(marker.read_text())
                with self.assertRaises(ProcessLookupError):
                    os.kill(provider_pid, 0)
            finally:
                if provider_pid:
                    try:
                        os.kill(provider_pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    def test_sigterm_unwinds_owned_runtime_and_private_directory(self):
        code = """
import importlib.util,pathlib,signal,sys,time
spec=importlib.util.spec_from_file_location('runtime',sys.argv[1])
runtime=importlib.util.module_from_spec(spec); spec.loader.exec_module(runtime)
def stop(*args): raise KeyboardInterrupt()
signal.signal(signal.SIGTERM,stop)
try:
    with runtime.OwnedRuntime([sys.executable,'-c','import time; time.sleep(60)'],{},pathlib.Path(sys.argv[2])) as process:
        pathlib.Path(sys.argv[3]).write_text(str(process.pid))
        time.sleep(60)
except KeyboardInterrupt:
    sys.exit(7)
"""
        with tempfile.TemporaryDirectory() as directory:
            private = Path(directory) / "private"
            private.mkdir()
            marker = Path(directory) / "pid"
            parent = subprocess.Popen([sys.executable, "-c", code, str(HELPER), str(private), str(marker)],
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            try:
                runtime.wait_for(marker.exists, 5, "TEST_PROCESS_START_TIMEOUT")
                pid = int(marker.read_text())
                parent.terminate()
                output, error = parent.communicate(timeout=15)
                self.assertEqual(parent.returncode, 7, error)
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)
                self.assertEqual(list(private.iterdir()), [])
            finally:
                if parent.poll() is None:
                    parent.kill()
                    parent.wait()

    def test_cleanup_reaps_orphaned_provider_after_daemon_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / "provider.pid"
            code = ("import subprocess,sys,os; "
                    "p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'],preexec_fn=os.setpgrp); "
                    "open(sys.argv[1],'w').write(str(p.pid))")
            provider_pid = None
            try:
                with runtime.OwnedRuntime([sys.executable, "-c", code, str(marker)], {}, Path(directory)) as process:
                    self.assertEqual(process.wait(timeout=5), 0)
                    provider_pid = int(marker.read_text())
                with self.assertRaises(ProcessLookupError):
                    os.kill(provider_pid, 0)
            finally:
                if provider_pid:
                    try:
                        os.kill(provider_pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    def test_wait_is_bounded_and_does_not_silently_pass(self):
        fn = self.function("wait_for")
        start = time.monotonic()
        with self.assertRaisesRegex(runtime.CheckFailure, "DISPATCH_TIMEOUT"):
            fn(lambda: None, .01, "DISPATCH_TIMEOUT")
        self.assertLess(time.monotonic() - start, 1)

    def test_evidence_drops_unapproved_secrets(self):
        fn = self.function("write_evidence")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runtime-dispatch.json"
            fn(path, {"verdict": "FAIL", "token": "SENTINEL_PRIVATE", "raw": "SENTINEL_PRIVATE"})
            self.assertNotIn("SENTINEL_PRIVATE", path.read_text())
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)

    def test_inherited_token_never_enters_argv_output_or_files(self):
        sentinel = "UNIT_TEST_CREDENTIAL_SENTINEL"
        with patch.dict(os.environ, {"QODER_PERSONAL_ACCESS_TOKEN": sentinel}):
            with tempfile.TemporaryDirectory() as directory:
                argv = [sys.executable, "-c",
                        "import os; assert os.environ.get('QODER_PERSONAL_ACCESS_TOKEN'); "
                        "print(os.environ['QODER_PERSONAL_ACCESS_TOKEN'])"]
                with runtime.OwnedRuntime(argv, {}, Path(directory)) as process:
                    self.assertEqual(process.wait(timeout=5), 0)
                    self.assertNotIn(sentinel, " ".join(process.args))
                self.assertEqual(list(Path(directory).iterdir()), [])

    def test_runtime_output_is_discarded_before_reaching_helper_stdout(self):
        code = """
import importlib.util,os,pathlib,sys,tempfile
spec=importlib.util.spec_from_file_location('runtime',sys.argv[1])
runtime=importlib.util.module_from_spec(spec); spec.loader.exec_module(runtime)
with tempfile.TemporaryDirectory() as directory:
    argv=[sys.executable,'-c',"import os,sys; print(os.environ['QODER_PERSONAL_ACCESS_TOKEN']); print(os.environ['QODER_PERSONAL_ACCESS_TOKEN'], file=sys.stderr)"]
    with runtime.OwnedRuntime(argv,{},pathlib.Path(directory)) as process:
        assert process.wait(timeout=5)==0
        for path in pathlib.Path(directory).rglob('*'):
            if path.is_file():
                assert b'UNIT_TEST_CREDENTIAL_SENTINEL' not in path.read_bytes()
"""
        env = dict(os.environ, QODER_PERSONAL_ACCESS_TOKEN="UNIT_TEST_CREDENTIAL_SENTINEL")
        result = subprocess.run([sys.executable, "-c", code, str(HELPER)], env=env,
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("UNIT_TEST_CREDENTIAL_SENTINEL", result.stdout + result.stderr)

    def test_failed_runtime_cleanup_is_not_reported_as_clean(self):
        class FakeApi:
            def __init__(self, base):
                pass
            def call(self, method, path, body=None):
                return {"id": 1, "accessToken": "unit-test-only", "onlineVersionId": 2,
                        "status": "ONLINE", "token": "unit-test-only"}
        class BrokenCleanup:
            cleaned = False
            def __init__(self, *args):
                pass
            def __enter__(self):
                return None
            def __exit__(self, *args):
                raise OSError("UNIT_TEST_PRIVATE_CLEANUP_ERROR")
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(runtime, "Api", FakeApi), patch.object(runtime, "OwnedRuntime", BrokenCleanup), \
                    patch.object(runtime, "wait_for", side_effect=runtime.CheckFailure("EXECUTOR_ONLINE_TIMEOUT")):
                with self.assertRaises(OSError):
                    runtime.run_scenario(Path("unused"), Path(directory),
                                         "http://127.0.0.1:7001", "http://127.0.0.1:9000")
            evidence = json.loads((Path(directory) / "runtime-dispatch.json").read_text())
            self.assertFalse(evidence["cleanup"])
            self.assertEqual(evidence["verdict"], "FAIL")
            self.assertNotIn("UNIT_TEST_PRIVATE_CLEANUP_ERROR", json.dumps(evidence))

    def test_artifact_request_omits_bearer_header(self):
        from http.server import BaseHTTPRequestHandler, HTTPServer
        import threading
        received = []
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                received.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"proof")
            def log_message(self, *args):
                pass
        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            origin = f"http://127.0.0.1:{server.server_port}"
            api = runtime.Api(origin)
            api.token = "UNIT_TEST_CREDENTIAL_SENTINEL"
            self.assertEqual(api.download(origin + "/artifact", origin), b"proof")
            self.assertEqual(received, [None])
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_api_does_not_expose_server_error_or_follow_redirect(self):
        from http.server import BaseHTTPRequestHandler, HTTPServer
        import threading
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(302)
                self.send_header("Location", "http://secret.invalid/UNIT_TEST_CREDENTIAL_SENTINEL")
                self.end_headers()
            def log_message(self, *args):
                pass
        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            api = runtime.Api(f"http://127.0.0.1:{server.server_port}")
            api.token = "UNIT_TEST_CREDENTIAL_SENTINEL"
            with self.assertRaisesRegex(runtime.CheckFailure, "HTTP_REDIRECT_REJECTED"):
                api.call("GET", "/redirect")
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


class PreflightTests(unittest.TestCase):
    def test_lifecycle_propagates_runtime_helper_failure(self):
        # Real lifecycle script; fake only external services and the expensive
        # real runtime boundary. This is not real execution evidence.
        with tempfile.TemporaryDirectory() as directory:
            fixture = Path(directory).resolve() / "project"
            e2e = fixture / "e2e-tests"
            shutil.copytree(ROOT / "e2e-tests", e2e)
            fake_bin = Path(directory) / "bin"
            fake_bin.mkdir()
            for name, code in {"docker": "#!/bin/sh\nexit 0\n", "curl": "#!/bin/sh\nprintf 200\n"}.items():
                path = fake_bin / name
                path.write_text(code)
                path.chmod(0o700)
            for name in ("authchain.sh", "logscan.sh"):
                (e2e / name).write_text("#!/bin/sh\nexit 0\n")
                (e2e / name).chmod(0o700)
            (e2e / "runtime_dispatch.py").write_text(
                "import sys\nsys.exit(0 if '--preflight' in sys.argv else 17)\n")
            state_base = Path(directory) / "state"
            namespace = state_base / hashlib.sha256(str(fixture).encode()).hexdigest()[:12]
            run = namespace / "test-run"
            (run / "logs").mkdir(parents=True)
            (namespace / "current").write_text("test-run\n")
            (run / "lifecycle.env").write_text(
                f"AW_E2E_STATE_DIR='{run}'\nAW_E2E_DOCKER='{fake_bin / 'docker'}'\n"
                "RESOLVED_APP_PORT=17001\nRESOLVED_MINIO_PORT=19000\n")
            env = dict(os.environ, AW_E2E_TEST_STATE_BASE=str(state_base),
                       PATH=str(fake_bin) + os.pathsep + os.environ["PATH"])
            result = subprocess.run(["bash", str(e2e / "verify.sh"), "--check", "--with-runtime",
                                     "--project-root", str(fixture)], env=env,
                                    text=True, capture_output=True, timeout=20)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("FAILED_PHASE=CHECK_RUNTIME", result.stdout)
            self.assertNotIn("VERDICT=PASS", result.stdout)
            evidence = json.loads((run / "result.json").read_text())
            self.assertEqual(evidence["verdict"], "FAIL")

    def test_runtime_self_check_must_succeed_before_resource_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            package = Path(directory)
            (package / "vendor").mkdir()
            (package / "package.json").write_text(json.dumps({"name": "autowonder", "version": "0.2.163"}))
            binary = package / "vendor/autowonder-daemon-darwin-arm64"
            binary.write_text("#!/bin/sh\nexit 1\n")
            binary.chmod(0o700)
            env = {"QODER_PERSONAL_ACCESS_TOKEN": "unit-test-only", "AW_E2E_RUNTIME_PACKAGE": directory}
            completed = subprocess.CompletedProcess([], 0, json.dumps({"package": "autowonder", "version": "0.2.163", "goos": "darwin", "goarch": "arm64"}))
            with patch.dict(os.environ, env), patch.object(runtime.platform, "system", return_value="Darwin"), \
                    patch.object(runtime.platform, "machine", return_value="arm64"), \
                    patch.object(runtime.shutil, "which", return_value="/test/qodercli"), \
                    patch.object(runtime.subprocess, "run", return_value=completed):
                self.assertEqual(runtime.preflight(), binary.resolve())
                completed.returncode = -9
                with self.assertRaisesRegex(runtime.CheckFailure, "RUNTIME_SELF_CHECK_FAILED"):
                    runtime.preflight()

    def test_missing_token_fails_explicitly(self):
        env = dict(os.environ)
        env.pop("QODER_PERSONAL_ACCESS_TOKEN", None)
        env.pop("TOKEN", None)
        result = subprocess.run([sys.executable, str(HELPER), "--preflight"],
                                env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("QODER_TOKEN_REQUIRED", result.stderr)

    def test_runtime_option_rejected_outside_check(self):
        result = subprocess.run(["bash", str(ROOT / "e2e-tests/verify.sh"),
                                 "--status", "--with-runtime", "--project-root", str(ROOT)],
                                capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--with-runtime requires --check", result.stderr)


if __name__ == "__main__":
    unittest.main()
