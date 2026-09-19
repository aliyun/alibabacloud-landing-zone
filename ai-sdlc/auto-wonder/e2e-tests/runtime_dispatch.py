#!/usr/bin/env python3
"""Opt-in real Qoder dispatch check. Never print raw exceptions or responses."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import platform
import hashlib
import re
import secrets
import signal
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
from urllib.parse import urlsplit

RUNTIME_VERSION = "0.2.163"


class CheckFailure(Exception):
    """Contains only a fixed, non-secret failure code."""


def require_api_success(body):
    if not isinstance(body, dict) or body.get("success") is not True:
        raise CheckFailure("API_BUSINESS_FAILURE")
    return body.get("data")


def require_artifact(actual, expected):
    if actual != expected:
        raise CheckFailure("ARTIFACT_CONTENT_MISMATCH")
    return hashlib.sha256(actual).hexdigest()


def step_request(instruction):
    # Routing columns are optional JSON, not enum strings. A one-step workflow
    # needs no explicit routing rule; the runtime completes its sole step.
    return {"name": "Deliver proof", "stepOrder": 1, "kind": "WORK", "code": "deliver-proof",
            "handlerType": "AGENT", "instructionMd": instruction, "required": True,
            "timeoutSeconds": 300, "retryBudget": 0}


def reserve_executor(api, agent_id, lock_root):
    # Fresh databases reuse numeric IDs. Never overwrite a host runtime lock
    # belonging to another server/run, even if that lock is currently inactive.
    for _ in range(32):
        executor = api.call("POST", f"/api/agents/{agent_id}/executors",
                           {"name": "runtime-e2e", "clientKind": "QODER_CLI"})
        if not (lock_root / f"executor-{int(executor['id'])}.lock").exists():
            return executor
        api.call("DELETE", f"/api/executors/{int(executor['id'])}")
    raise CheckFailure("ISOLATED_EXECUTOR_ID_UNAVAILABLE")


def require_dispatch(dispatch, expected):
    if dispatch.get("status") != "SUCCEEDED":
        raise CheckFailure("DISPATCH_NOT_SUCCEEDED")
    if any(dispatch.get(key) != value for key, value in expected.items()):
        raise CheckFailure("DISPATCH_ROUTING_MISMATCH")


def validate_download(url, origin):
    parsed, allowed = urlsplit(url), urlsplit(origin)
    if (parsed.scheme != "http" or parsed.hostname != "127.0.0.1"
            or parsed.netloc != allowed.netloc or parsed.username or parsed.password
            or parsed.fragment):
        raise CheckFailure("DOWNLOAD_DESTINATION_REJECTED")


def select_artifact(artifacts, dispatch_id):
    matches = [item for item in artifacts if item.get("dispatchId") == dispatch_id
               and Path(item.get("name", "")).name == "runtime-proof.txt"]
    if len(matches) != 1:
        raise CheckFailure("ARTIFACT_MISSING_OR_AMBIGUOUS")
    return matches[0]["id"]


def require_transitions(log, dispatch_id):
    text = log.read_text(errors="replace")
    for status in ("ACKED", "RUNNING",):
        if not re.search(rf"dispatch transition dispatchId={int(dispatch_id)} status={status}\b", text):
            raise CheckFailure("DISPATCH_TRANSITION_EVIDENCE_MISSING")


def wait_for(probe, timeout, failure):
    deadline = time.monotonic() + timeout
    while True:
        result = probe()
        if result:
            return result
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise CheckFailure(failure)
        time.sleep(min(.5, remaining))


EVIDENCE_KEYS = frozenset(("verdict", "runtimeVersion", "workspaceId", "agentId",
    "agentVersionId", "executorId", "workitemId", "dispatchId", "stepId", "artifactId",
    "artifactSha256", "checks", "cleanup", "failureKind"))


def write_evidence(path, evidence):
    with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as stream:
        json.dump({k: v for k, v in evidence.items() if k in EVIDENCE_KEYS}, stream, indent=2)
        stream.write("\n")


class OwnedRuntime:
    """Foreground child and private work directory; never touches global CLI state."""
    def __init__(self, argv, connection_env, parent):
        self.argv, self.connection_env, self.parent = argv, connection_env, parent
        self.process = None
        self.directory = None
        self.cleaned = False
        self.lock_path = None

    def __enter__(self):
        executor_id = self.connection_env.get("AUTOWONDER_EXECUTOR_ID")
        if executor_id:
            self.lock_path = Path.home() / ".autowonder/run" / f"executor-{int(executor_id)}.lock"
            if self.lock_path.exists() or self.lock_path.is_symlink():
                raise CheckFailure("RUNTIME_LOCK_ALREADY_EXISTS")
        self.directory = tempfile.TemporaryDirectory(prefix="runtime-private-", dir=self.parent)
        root = self.directory.name
        # Do not forward unrelated credentials or runtime settings from the host.
        env = {k: os.environ[k] for k in ("PATH", "HOME", "TMPDIR", "LANG",
               "QODER_PERSONAL_ACCESS_TOKEN", "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY") if k in os.environ}
        env.update(self.connection_env)
        env.update(AUTOWONDER_WORKSPACE_ROOT=root + "/workspaces",
                   AUTOWONDER_ASSIGNMENT_QUEUE_DIR=root + "/assignments",
                   AUTOWONDER_LOCAL_API_ADDR="127.0.0.1:0",
                   AUTOWONDER_MEMORY_MODE="none", AUTOWONDER_MAX_CONCURRENT_DISPATCHES="1",
                   QODER_CONFIG_DIR=root + "/qoder", AUTOWONDER_AUTO_UPDATE_INITIAL_DELAY="24h")
        try:
            self.process = subprocess.Popen(self.argv, env=env, cwd=root,
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                start_new_session=True)
        except BaseException:
            self.directory.cleanup()
            raise
        return self.process

    def __exit__(self, *unused):
        # Qoder creates its own process group. Snapshot numeric ancestry before
        # stopping the daemon; never inspect command lines or match process names.
        rows = subprocess.check_output(["ps", "-axo", "pid=,ppid=,pgid="], text=True)
        table = [tuple(map(int, line.split())) for line in rows.splitlines() if line.strip()]
        descendants = {self.process.pid}
        while True:
            expanded = descendants | {pid for pid, ppid, _ in table if ppid in descendants}
            if expanded == descendants:
                break
            descendants = expanded
        groups = {pgid for pid, _, pgid in table if pid in descendants and pgid in descendants}
        # A crashed daemon may have orphaned Qoder before __exit__. Its separate
        # group still belongs to the private session created by Popen.
        for pid, _, pgid in table:
            try:
                if os.getsid(pid) == self.process.pid:
                    groups.add(pgid)
                    descendants.add(pid)
            except (ProcessLookupError, PermissionError):
                pass
        groups.add(self.process.pid)
        def live_groups():
            live = set()
            for pid in descendants:
                try:
                    pgid = os.getpgid(pid)
                    if pgid in groups:
                        live.add(pgid)
                except ProcessLookupError:
                    pass
            return live
        def send(sig):
            for pgid in live_groups():
                try:
                    os.killpg(pgid, sig)
                except ProcessLookupError:
                    pass
        try:
            send(signal.SIGTERM)
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                pass
            send(signal.SIGKILL)
            self.process.wait(timeout=5)
            def all_gone():
                return not live_groups()
            wait_for(all_gone, 5, "RUNTIME_PROCESS_CLEANUP_FAILED")
            if self.lock_path and self.lock_path.exists():
                # Remove only a new lock whose owner PID is our exact child.
                with self.lock_path.open("r") as stream:
                    fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    if stream.read().strip() != str(self.process.pid):
                        raise CheckFailure("RUNTIME_LOCK_OWNER_CHANGED")
                    self.lock_path.unlink()
        finally:
            self.directory.cleanup()
        self.cleaned = True


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise CheckFailure("HTTP_REDIRECT_REJECTED")


class Api:
    def __init__(self, base):
        parsed = urlsplit(base)
        if (parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or not parsed.port
                or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment):
            raise CheckFailure("LOCAL_BASE_URL_REQUIRED")
        self.base, self.token = base, None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def call(self, method, path, body=None):
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        request = urllib.request.Request(self.base + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=20) as response:
                return require_api_success(json.load(response))
        except CheckFailure:
            raise
        except Exception:
            raise CheckFailure("API_TRANSPORT_FAILURE") from None

    def download(self, url, origin):
        validate_download(url, origin)
        try:
            with self.opener.open(urllib.request.Request(url), timeout=20) as response:
                return response.read(4097)
        except CheckFailure:
            raise
        except Exception:
            raise CheckFailure("ARTIFACT_DOWNLOAD_FAILED") from None


def run_scenario(binary, state, base, storage_origin):
    if not state or not state.is_dir() or state.is_symlink():
        raise CheckFailure("LIFECYCLE_STATE_REQUIRED")
    api = Api(base)
    validate_download(storage_origin, storage_origin)
    evidence = {"verdict": "FAIL", "runtimeVersion": RUNTIME_VERSION, "checks": [], "cleanup": False}
    executor_id = None
    owned = None
    try:
        print("RUNTIME_PHASE=CREATE_TEST_OBJECTS", flush=True)
        suffix = secrets.token_hex(8)
        username, password = "runtime-e2e-" + suffix, secrets.token_urlsafe(24)
        api.call("POST", "/api/auth/register", {"username": username, "password": password,
            "email": username + "@example.invalid", "nickname": "Runtime E2E"})
        api.token = api.call("POST", "/api/auth/login", {"username": username, "password": password})["accessToken"]
        workspace_id = api.call("POST", "/api/workspaces", {"name": "Runtime E2E " + suffix})["id"]
        evidence["workspaceId"] = workspace_id
        api.token = api.call("POST", f"/api/workspaces/{workspace_id}/switch")["accessToken"]
        marker = "AW_RUNTIME_PROOF_" + secrets.token_hex(16)
        expected = (marker + "\n").encode()
        instruction = ("This is a bounded smoke test. Use your file/shell tool to create "
            "artifacts/output/deliverables/runtime-proof.txt in the runtime capsule. "
            "Its entire UTF-8 contents must be exactly the following line plus one LF newline: "
            + marker + ". Do not include this sentence's final period in the file. "
            "Read back the file and verify it. Report completion using the runtime output contract. "
            "Do not access credentials, print environment variables, change other files, "
            "or contact external services. No additional tasks are needed.")
        sdlc_id = api.call("POST", "/api/sdlcs", {"name": "Runtime smoke " + suffix,
            "workType": "TASK", "description": "Single deterministic delivery step"})["id"]
        step_id = api.call("POST", f"/api/sdlcs/{sdlc_id}/steps", step_request(instruction))["id"]
        evidence["stepId"] = step_id
        api.call("POST", f"/api/sdlcs/{sdlc_id}/enable", {})
        config = {"roleName": "Runtime Proof", "roleCode": "RUNTIME_E2E",
            "businessBackground": "Run only the assigned bounded local smoke task.",
            "responsibilities": "Follow the SDLC step. Never inspect or emit credentials.",
            "sdlcId": sdlc_id, "evolutionMode": "MANUAL"}
        agent_id = api.call("POST", "/api/agents", dict(config, name="Runtime E2E " + suffix))["id"]
        evidence["agentId"] = agent_id
        api.call("PUT", f"/api/agents/{agent_id}/config", config)
        api.call("POST", f"/api/agents/{agent_id}/submit", {})
        api.call("POST", f"/api/agents/{agent_id}/approve", {"comment": "Disposable runtime smoke"})
        agent = api.call("GET", f"/api/agents/{agent_id}")
        if agent.get("status") != "ONLINE" or not agent.get("onlineVersionId"):
            raise CheckFailure("AGENT_ONLINE_VERSION_REQUIRED")
        evidence["agentVersionId"] = agent["onlineVersionId"]
        executor = reserve_executor(api, agent_id, Path.home() / ".autowonder/run")
        executor_id = evidence["executorId"] = executor["id"]
        connection = {"AUTOWONDER_PROVIDER": "qoder", "AUTOWONDER_EXECUTOR_ID": str(executor_id),
            "AUTOWONDER_EXECUTOR_TOKEN": executor["token"],
            "AUTOWONDER_SERVER_WS_URL": base.replace("http://", "ws://", 1) + "/ws/executor",
            "AUTOWONDER_DAEMON_ID": "e2e-" + suffix, "AUTOWONDER_RUNTIME_ID": "e2e-" + suffix}
        print("RUNTIME_PHASE=WAIT_ONLINE", flush=True)
        owned = OwnedRuntime([str(binary)], connection, state)
        with owned as process:
            def online():
                if process.poll() is not None:
                    raise CheckFailure("RUNTIME_EXITED")
                return any(e["id"] == executor_id and e.get("status") == "ONLINE"
                    for e in api.call("GET", f"/api/agents/{agent_id}/executors"))
            wait_for(online, 45, "EXECUTOR_ONLINE_TIMEOUT")
            evidence["checks"].append("executor_online")
            workitem_id = api.call("POST", "/api/workitems", {"workType": "TASK",
                "title": "Runtime delivery " + suffix, "contentMd": instruction, "priority": 2})["id"]
            evidence["workitemId"] = workitem_id
            api.call("PUT", f"/api/workitems/{workitem_id}/assignee",
                {"assigneeType": "AGENT", "assigneeRef": agent_id, "sdlcId": sdlc_id})
            print("RUNTIME_PHASE=WAIT_DISPATCH", flush=True)
            def completed():
                if process.poll() is not None:
                    raise CheckFailure("RUNTIME_EXITED")
                rows = api.call("GET", f"/api/dispatches?workitem_id={workitem_id}&page_size=50")["list"]
                if len(rows) > 1:
                    raise CheckFailure("UNEXPECTED_MULTIPLE_DISPATCHES")
                if not rows:
                    return None
                row = rows[0]
                evidence["dispatchId"] = row["id"]
                if row.get("status") in ("FAILED", "TIMEOUT", "CANCELLED"):
                    raise CheckFailure("DISPATCH_TERMINAL_FAILURE")
                return row if row.get("status") == "SUCCEEDED" else None
            dispatch = wait_for(completed, 360, "DISPATCH_TIMEOUT")
            require_dispatch(dispatch, {"workitemId": workitem_id, "agentId": agent_id,
                "agentVersionId": agent["onlineVersionId"], "executorId": executor_id, "sdlcStepId": step_id})
            require_transitions(state / "logs/auto-wonder.log", dispatch["id"])
            evidence["checks"].extend(["routing_matches", "server_acked", "server_running", "dispatch_succeeded"])
            artifact_id = select_artifact(api.call("GET", f"/api/workitems/{workitem_id}/artifacts"), dispatch["id"])
            evidence["artifactId"] = artifact_id
            url = api.call("GET", f"/api/artifacts/{artifact_id}/download")
            evidence["artifactSha256"] = require_artifact(api.download(url, storage_origin), expected)
            evidence["checks"].append("downloaded_artifact_matches")
        evidence["verdict"] = "PASS"
    except CheckFailure as exc:
        evidence["failureKind"] = str(exc)
        raise
    except BaseException:
        evidence["failureKind"] = "RUNTIME_CHECK_INTERRUPTED_OR_INTERNAL"
        raise
    finally:
        try:
            if executor_id:
                api.call("DELETE", f"/api/executors/{executor_id}")
            evidence["cleanup"] = owned is None or owned.cleaned
            if not evidence["cleanup"]:
                evidence["verdict"] = "FAIL"
                evidence["failureKind"] = "RUNTIME_CLEANUP_FAILED"
        except Exception:
            evidence["verdict"] = "FAIL"
            evidence["failureKind"] = "EXECUTOR_CLEANUP_FAILED"
        write_evidence(state / "runtime-dispatch.json", evidence)
    if evidence["verdict"] != "PASS":
        raise CheckFailure("EXECUTOR_CLEANUP_FAILED")
    print("RUNTIME_DISPATCH=PASS", flush=True)


def preflight():
    if not os.environ.get("QODER_PERSONAL_ACCESS_TOKEN", "").strip():
        raise CheckFailure("QODER_TOKEN_REQUIRED")
    if not shutil.which("qodercli"):
        raise CheckFailure("QODER_CLI_REQUIRED")
    package = Path(os.environ.get("AW_E2E_RUNTIME_PACKAGE", ""))
    try:
        metadata = json.loads((package / "package.json").read_text())
    except (OSError, ValueError):
        raise CheckFailure("RUNTIME_PACKAGE_REQUIRED") from None
    if metadata.get("name") != "autowonder" or metadata.get("version") != RUNTIME_VERSION:
        raise CheckFailure("RUNTIME_VERSION_MISMATCH")
    system = platform.system().lower()
    arch = {"arm64": "arm64", "aarch64": "arm64", "x86_64": "amd64"}.get(platform.machine())
    if system not in ("darwin", "linux") or not arch:
        raise CheckFailure("RUNTIME_PLATFORM_UNSUPPORTED")
    binary = package / "vendor" / f"autowonder-daemon-{system}-{arch}"
    if not binary.is_file() or not os.access(binary, os.X_OK):
        raise CheckFailure("RUNTIME_BINARY_REQUIRED")
    try:
        # Self-check needs no credentials; never forward the user's token here.
        check = subprocess.run([str(binary.resolve()), "--self-check", "--expected-version", RUNTIME_VERSION],
            env={"PATH": os.environ.get("PATH", "")}, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=20)
        identity = json.loads(check.stdout) if check.returncode == 0 else {}
        if any(identity.get(key) != expected for key, expected in
               (("package", "autowonder"), ("version", RUNTIME_VERSION), ("goos", system), ("goarch", arch))):
            raise CheckFailure("RUNTIME_SELF_CHECK_FAILED")
    except (OSError, ValueError, subprocess.TimeoutExpired):
        raise CheckFailure("RUNTIME_SELF_CHECK_FAILED") from None
    return binary.resolve()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--run", action="store_true")
    parser.add_argument("--state-dir", type=Path)
    parser.add_argument("--base-url")
    parser.add_argument("--storage-origin")
    args = parser.parse_args()
    try:
        binary = preflight()
        if args.run:
            run_scenario(binary, args.state_dir, args.base_url, args.storage_origin)
            return 0
        print("RUNTIME_PREFLIGHT=PASS")
        return 0
    except CheckFailure as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except Exception:
        print("RUNTIME_CHECK_INTERNAL_FAILURE", file=sys.stderr)
        return 1


if __name__ == "__main__":
    def interrupted(signum, frame):
        raise CheckFailure("RUNTIME_CHECK_INTERRUPTED")
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    sys.exit(main())
