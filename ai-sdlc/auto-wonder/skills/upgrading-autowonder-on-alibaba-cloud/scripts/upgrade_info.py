#!/usr/bin/env python3
"""Persist and refresh sanitized AutoWonder upgrade discovery information."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, List, Optional, Tuple


DEPLOYMENT_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
SEMANTIC_VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$")
REQUIRED_TAGS = ("Project", "DeploymentId", "Environment", "ManagedBy", "Topology")
IGNORED_DIRECTORIES = {".git", ".terraform", "upgrade-info", ".worktrees"}


class UpgradeInfoError(RuntimeError):
    pass


class DeploymentFolderRequired(UpgradeInfoError):
    pass


class MultipleDeployments(UpgradeInfoError):
    pass


def protected_deployment_root() -> Path:
    return (Path(__file__).resolve().parents[3] / "deployments").resolve()


def is_protected_deployment_path(path: Path) -> bool:
    try:
        path.resolve(strict=True).relative_to(protected_deployment_root())
        return True
    except (OSError, ValueError):
        return False


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def canonical_json(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> Dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise UpgradeInfoError("Cannot read required deployment metadata") from exc
    if not isinstance(value, dict):
        raise UpgradeInfoError("Deployment metadata must be a JSON object")
    return value


def relative_string(root: Path, path: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError as exc:
        raise UpgradeInfoError("Recorded paths must stay inside the project root") from exc


def resolve_project_path(root: Path, value: str) -> Path:
    root = root.resolve(strict=True)
    supplied = Path(value)
    if supplied.is_absolute():
        if not is_protected_deployment_path(supplied):
            raise UpgradeInfoError("Deployment directory must stay inside the project root or protected AutoWonder deployment root")
        resolved = supplied.resolve(strict=True)
        if not resolved.is_dir() or resolved.is_symlink():
            raise UpgradeInfoError("Deployment directory was not found")
        return resolved
    if ".." in supplied.parts:
        raise UpgradeInfoError("Deployment directory must stay inside the project root")
    candidate = root / supplied
    current = root
    for part in supplied.parts:
        current = current / part
        if current.is_symlink():
            raise UpgradeInfoError("Deployment directory must not contain a symbolic link")
    try:
        resolved = candidate.resolve(strict=True)
    except OSError as exc:
        raise UpgradeInfoError("Deployment directory was not found") from exc
    if not resolved.is_dir():
        raise UpgradeInfoError("Deployment directory was not found")
    try:
        resolved.relative_to(root)
    except ValueError as exc:
        raise UpgradeInfoError("Deployment directory must stay inside the project root") from exc
    return resolved


def walk_files(root: Path, pattern: str) -> Iterable[Path]:
    for path in root.rglob(pattern):
        if any(part in IGNORED_DIRECTORIES for part in path.relative_to(root).parts):
            continue
        if path.is_file() and not path.is_symlink():
            yield path


def terraform_roots(deployment_dir: Path) -> List[Tuple[Path, str, Optional[Path]]]:
    roots: List[Tuple[Path, str, Optional[Path]]] = []
    parents = sorted({path.parent for path in walk_files(deployment_dir, "*.tf")})
    backend_files = sorted(walk_files(deployment_dir, "backend.hcl"))
    for parent in parents:
        local_states = sorted(
            path for path in parent.glob("*.tfstate") if path.is_file() and not path.is_symlink()
        )
        declares_oss = any(
            re.search(r'backend\s+"oss"', path.read_text(encoding="utf-8", errors="ignore"))
            for path in parent.glob("*.tf")
        )
        backend_directories = {parent}
        backend_directories.update(
            ancestor
            for ancestor in parent.parents
            if ancestor == deployment_dir or deployment_dir in ancestor.parents
        )
        nearby_backends = [path for path in backend_files if path.parent in backend_directories]
        has_remote = declares_oss or bool(nearby_backends)
        has_local = bool(local_states)
        if has_remote and has_local:
            raise UpgradeInfoError("Terraform root contains conflicting local and OSS state configuration")
        if has_local:
            if len(local_states) != 1:
                raise UpgradeInfoError("Terraform root contains multiple local state files")
            roots.append((parent, "local", local_states[0]))
        elif has_remote:
            if len(nearby_backends) > 1:
                raise UpgradeInfoError("Terraform root contains multiple OSS backend configurations")
            roots.append((parent, "oss", nearby_backends[0] if nearby_backends else None))
    return roots


def choose_terraform_root(deployment_dir: Path) -> Tuple[Path, str, Optional[Path]]:
    roots = terraform_roots(deployment_dir)
    if not roots:
        raise UpgradeInfoError("No usable Terraform root was found")
    if len(roots) > 1:
        raise UpgradeInfoError("Deployment directory contains multiple Terraform roots")
    return roots[0]


def deployment_metadata_candidates(deployment_dir: Path) -> List[Dict[str, Any]]:
    candidates: List[Dict[str, Any]] = []
    for path in walk_files(deployment_dir, "*.json"):
        if path.name in {"inventory.json", "deployment.auto.tfvars.json"}:
            continue
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(value, dict):
            continue
        deployment_id = value.get("deploymentId") or nested(value, "tags", "DeploymentId")
        if isinstance(deployment_id, str) and DEPLOYMENT_ID_RE.fullmatch(deployment_id):
            candidates.append(value)
    return candidates


def nested(value: Any, *keys: str) -> Any:
    current = value
    for key in keys:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def unique_value(label: str, values: Iterable[Any], required: bool = True) -> Any:
    present = [value for value in values if value not in (None, "", {}, [])]
    canonical = {json.dumps(value, sort_keys=True) for value in present}
    if len(canonical) > 1:
        raise UpgradeInfoError("Deployment metadata contains conflicting " + label)
    if not present:
        if required:
            raise UpgradeInfoError("Deployment metadata is missing " + label)
        return None
    return present[0]


def find_unique_file(root: Path, name: str, required: bool = True) -> Optional[Path]:
    matches = sorted(walk_files(root, name))
    if len(matches) > 1:
        raise UpgradeInfoError("Deployment directory contains multiple required files")
    if not matches:
        if required:
            raise UpgradeInfoError("Deployment directory is missing a required file")
        return None
    return matches[0]


def extract_context(project_root: Path, deployment_dir: Path) -> Dict[str, Any]:
    terraform_dir, backend_mode, state_file = choose_terraform_root(deployment_dir)
    tfvars_path = find_unique_file(deployment_dir, "deployment.auto.tfvars.json")
    inventory_path = find_unique_file(deployment_dir, "inventory.json")
    protected_env = find_unique_file(deployment_dir, "autowonder.env")
    tfvars = load_json(tfvars_path)
    inventory = load_json(inventory_path)
    candidates = deployment_metadata_candidates(deployment_dir)
    if not candidates:
        raise UpgradeInfoError("Deployment directory has no usable deployment metadata")

    deployment_id = unique_value(
        "deployment ID",
        [item.get("deploymentId") for item in candidates]
        + [tfvars.get("deployment_id"), nested(inventory, "expected_tags", "DeploymentId")],
    )
    if not isinstance(deployment_id, str) or not DEPLOYMENT_ID_RE.fullmatch(deployment_id):
        raise UpgradeInfoError("Deployment ID is invalid")
    environment = unique_value(
        "environment",
        [item.get("environment") for item in candidates]
        + [tfvars.get("environment"), nested(inventory, "expected_tags", "Environment")],
    )
    region = unique_value(
        "region",
        [item.get("region") for item in candidates] + [tfvars.get("region"), inventory.get("region")],
    )
    tags = unique_value(
        "tags",
        [item.get("tags") for item in candidates]
        + [tfvars.get("common_tags"), inventory.get("expected_tags")],
    )
    if not isinstance(tags, dict) or any(not tags.get(key) for key in REQUIRED_TAGS):
        raise UpgradeInfoError("Deployment metadata is missing required identity tags")
    if tags["DeploymentId"] != deployment_id or tags["Environment"] != environment:
        raise UpgradeInfoError("Deployment identity tags conflict with metadata")
    repository_url = unique_value(
        "repository URL", [item.get("repositoryUrl") for item in candidates]
    )
    commit = unique_value(
        "active commit",
        [item.get("repositoryCommit") or nested(item, "deployment", "activeCommit") for item in candidates],
        required=False,
    )
    if commit is not None and (not isinstance(commit, str) or not COMMIT_RE.fullmatch(commit)):
        raise UpgradeInfoError("Deployment active commit is invalid")
    profile = unique_value(
        "cloud profile", [item.get("cloudProfile") for item in candidates], required=False
    ) or "default"
    recommended_runtime_version = unique_value(
        "recommended runtime version",
        [item.get("recommendedRuntimeVersion") for item in candidates],
        required=False,
    )
    if recommended_runtime_version is not None and (
        not isinstance(recommended_runtime_version, str)
        or not SEMANTIC_VERSION_RE.fullmatch(recommended_runtime_version)
    ):
        raise UpgradeInfoError("Recommended runtime version is invalid")
    resources = sanitize_inventory(inventory)
    return {
        "deploymentId": deployment_id,
        "environment": environment,
        "region": region,
        "cloudProfile": profile,
        "repositoryUrl": repository_url,
        "activeCommit": commit,
        "recommendedRuntimeVersion": recommended_runtime_version,
        "tags": {key: tags[key] for key in REQUIRED_TAGS},
        "resources": resources,
        "deploymentDirectory": relative_string(project_root, deployment_dir),
        "terraformDirectory": relative_string(project_root, terraform_dir),
        "backendMode": backend_mode,
        "stateFile": relative_string(project_root, state_file) if state_file else None,
        "protectedEnvFile": relative_string(project_root, protected_env),
        "tfvarsFile": relative_string(project_root, tfvars_path),
        "inventoryFile": relative_string(project_root, inventory_path),
    }


def sanitize_inventory(inventory: Dict[str, Any]) -> Dict[str, Any]:
    allowed = (
        "region", "vpc_id", "vswitch_ids", "ecs_instance_ids", "ecs_private_ips",
        "alb_id", "alb_address", "package_bucket", "artifact_bucket", "oss_endpoint",
        "oss_public_endpoint", "expected_tags",
    )
    result = {key: inventory[key] for key in allowed if inventory.get(key) not in (None, "", {}, [])}
    nested_allowed = {
        "rds": ("account", "connection", "database", "instance_id", "port"),
        "redis": ("connection", "instance_id", "port"),
        "oss": ("artifact_bucket", "control_endpoint", "package_bucket", "runtime_endpoint"),
        "sls": ("project", "logstore", "stores", "control_endpoint", "runtime_endpoint"),
    }
    for section, keys in nested_allowed.items():
        source = inventory.get(section)
        if isinstance(source, dict):
            result[section] = {
                key: source[key] for key in keys if source.get(key) not in (None, "", {}, [])
            }
    oss = result.get("oss")
    if isinstance(oss, dict):
        for key in ("package_bucket", "artifact_bucket"):
            if key not in result and oss.get(key):
                result[key] = oss[key]
    return result


def normalize_ecs(value: Any) -> Tuple[Dict[str, str], List[str]]:
    if isinstance(value, list):
        topology = {"node_" + str(index + 1): item for index, item in enumerate(value)}
    elif isinstance(value, dict):
        topology = dict(value)
    else:
        raise UpgradeInfoError("Terraform ECS output is empty or invalid")
    ids = sorted(set(topology.values()))
    if not ids or any(not isinstance(item, str) or not item for item in ids):
        raise UpgradeInfoError("Terraform ECS output is empty or invalid")
    return topology, ids


def build_inventory(previous: Optional[Dict[str, Any]], context: Dict[str, Any], resources: Dict[str, Any]) -> Dict[str, Any]:
    topology, current_ids = normalize_ecs(resources.get("ecs_instance_ids"))
    previous_ids = []
    if previous:
        previous_ids = list(nested(previous, "resources", "ecsInstanceIds") or [])
    fingerprint_material = {
        "deploymentId": context["deploymentId"],
        "region": context["region"],
        "vpcId": resources.get("vpc_id"),
        "ecsInstanceIds": current_ids,
    }
    fingerprint = sha256_bytes(canonical_json(fingerprint_material))
    previous_fingerprint = previous.get("resourceSetFingerprint") if previous else None
    added = sorted(set(current_ids) - set(previous_ids))
    removed = sorted(set(previous_ids) - set(current_ids))
    change_type = "unchanged"
    if added and not removed:
        change_type = "scale-out"
    elif removed and not added:
        change_type = "scale-in"
    elif added or removed:
        change_type = "changed"
    public_resources = {
        "vpcId": resources.get("vpc_id"),
        "vswitchIds": resources.get("vswitch_ids", []),
        "ecsInstanceIds": current_ids,
        "ecsTopology": topology,
    }
    for source, target in (
        ("alb_id", "albId"), ("alb_address", "albAddress"),
        ("rds", "rds"), ("redis", "redis"), ("oss", "oss"), ("sls", "sls"),
    ):
        if resources.get(source) not in (None, "", {}, []):
            public_resources[target] = resources[source]
    return {
        "schemaVersion": 1,
        "deploymentId": context["deploymentId"],
        "region": context["region"],
        "refreshedAt": utc_now(),
        "source": {
            "backendMode": context["backendMode"],
            "terraformDirectory": context["terraformDirectory"],
            "workspace": "default",
            "discoveryRuleRevision": 1,
        },
        "resources": public_resources,
        "resourceSetFingerprint": fingerprint,
        "previousResourceSetFingerprint": previous_fingerprint,
        "change": {
            "type": change_type,
            "addedEcsInstanceIds": added,
            "removedEcsInstanceIds": removed,
            "unchangedEcsInstanceIds": sorted(set(current_ids) & set(previous_ids)),
        },
        "nodeCount": {"previous": len(previous_ids), "current": len(current_ids)},
        "cloudVerification": {"status": "pending"},
        "scaleValidation": {"status": "pending" if added else "not-required"},
    }


def terraform_value(outputs: Dict[str, Any], name: str) -> Any:
    item = outputs.get(name)
    if not isinstance(item, dict) or "value" not in item:
        return None
    return item["value"]


def normalize_terraform_outputs(outputs: Dict[str, Any], bindings: Dict[str, str]) -> Dict[str, Any]:
    field_names = {
        "region": "region",
        "vpcId": "vpc_id",
        "vswitchIds": "vswitch_ids",
        "ecsInstanceIds": "ecs_instance_ids",
        "ecsPrivateIps": "ecs_private_ips",
        "albId": "alb_id",
        "albAddress": "alb_address",
        "rds": "rds",
        "redis": "redis",
        "oss": "oss",
        "sls": "sls",
        "expectedTags": "expected_tags",
    }
    projected: Dict[str, Any] = {}
    for canonical, target in field_names.items():
        binding = bindings.get(canonical)
        if not binding:
            continue
        value = terraform_value(outputs, binding)
        if value not in (None, "", {}, []):
            projected[target] = value
    for required in ("region", "vpc_id", "ecs_instance_ids"):
        if projected.get(required) in (None, "", {}, []):
            raise UpgradeInfoError("Terraform output is missing a required resource field")
    normalize_ecs(projected["ecs_instance_ids"])
    return sanitize_inventory(projected)


def run_command(command: List[str], environment: Dict[str, str], output_file: Optional[Path] = None) -> None:
    if output_file is None:
        result = subprocess.run(
            command,
            env=environment,
            text=True,
            encoding="utf-8",
            errors="replace",
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
    else:
        descriptor = os.open(str(output_file), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            result = subprocess.run(
                command,
                env=environment,
                text=True,
                encoding="utf-8",
                errors="replace",
                stdout=handle,
                stderr=subprocess.PIPE,
            )
    if result.returncode != 0:
        raise UpgradeInfoError("Terraform command failed during protected inventory refresh")


def run_terraform_output(project_root: Path, discovery: Dict[str, Any]) -> Dict[str, Any]:
    terraform = discovery.get("terraform")
    if not isinstance(terraform, dict):
        raise UpgradeInfoError("Discovery record is missing Terraform context")
    terraform_dir = resolve_project_path(project_root, str(terraform.get("workingDirectory") or ""))
    backend_mode = terraform.get("backendMode")
    if backend_mode not in {"local", "oss"}:
        raise UpgradeInfoError("Discovery record contains an unsupported Terraform backend")
    with tempfile.TemporaryDirectory(prefix="autowonder-upgrade-") as temporary_name:
        temporary = Path(temporary_name)
        os.chmod(temporary, 0o700)
        terraform_data = temporary / "terraform-data"
        terraform_data.mkdir(mode=0o700)
        environment = dict(os.environ)
        environment["TF_DATA_DIR"] = str(terraform_data)
        init_command = [
            "terraform", "-chdir=" + str(terraform_dir), "init", "-reconfigure", "-input=false",
            "-force-copy",
        ]
        if backend_mode == "oss":
            backend_reference = terraform.get("backendConfigFile")
            if not isinstance(backend_reference, str) or not backend_reference:
                raise UpgradeInfoError("OSS backend configuration is missing")
            backend_source = resolve_project_path_file(project_root, backend_reference)
            backend_copy = temporary / "backend.hcl"
            shutil.copyfile(backend_source, backend_copy)
            os.chmod(backend_copy, 0o600)
            init_command.append("-backend-config=" + str(backend_copy))
        run_command(init_command, environment)
        workspace = terraform.get("workspace") or "default"
        if workspace != "default":
            run_command(
                ["terraform", "-chdir=" + str(terraform_dir), "workspace", "select", str(workspace)],
                environment,
            )
        output_file = temporary / "terraform-output.json"
        run_command(
            ["terraform", "-chdir=" + str(terraform_dir), "output", "-json"],
            environment,
            output_file,
        )
        outputs = load_json(output_file)
        return normalize_terraform_outputs(outputs, discovery.get("outputBindings") or {})


def resolve_project_path_file(root: Path, value: str) -> Path:
    root = root.resolve(strict=True)
    supplied = Path(value)
    if supplied.is_absolute():
        if not is_protected_deployment_path(supplied):
            raise UpgradeInfoError("Recorded file must stay inside the project root or protected AutoWonder deployment root")
        resolved = supplied.resolve(strict=True)
        if resolved.is_symlink() or not resolved.is_file():
            raise UpgradeInfoError("Recorded file is unavailable")
        return resolved
    if ".." in supplied.parts:
        raise UpgradeInfoError("Recorded file must stay inside the project root")
    current = root
    for part in supplied.parts:
        current = current / part
        if current.is_symlink():
            raise UpgradeInfoError("Recorded file must not contain a symbolic link")
    try:
        resolved = (root / supplied).resolve(strict=True)
        resolved.relative_to(root)
    except (OSError, ValueError) as exc:
        raise UpgradeInfoError("Recorded file is unavailable inside the project root") from exc
    if not resolved.is_file():
        raise UpgradeInfoError("Recorded file is unavailable inside the project root")
    return resolved


def resolve_supplied_file(root: Path, value: str) -> Path:
    root = root.resolve(strict=True)
    supplied = Path(value).expanduser()
    candidate = supplied if supplied.is_absolute() else root / supplied
    try:
        resolved = candidate.resolve(strict=True)
        if not is_protected_deployment_path(resolved):
            resolved.relative_to(root)
    except (OSError, ValueError) as exc:
        raise UpgradeInfoError("Supplied file must stay inside the project root or protected AutoWonder deployment root") from exc
    if resolved.is_symlink() or not resolved.is_file():
        raise UpgradeInfoError("Supplied file is unavailable")
    return resolved


def atomic_write_json(path: Path, value: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    descriptor, temporary_name = tempfile.mkstemp(prefix=".tmp-", dir=str(path.parent))
    temporary = Path(temporary_name)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            json.dump(value, handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(str(temporary), str(path))
        os.chmod(path, 0o600)
    finally:
        if temporary.exists():
            temporary.unlink()


def build_discovery(project_root: Path, context: Dict[str, Any]) -> Dict[str, Any]:
    state_path = context["stateFile"]
    state_hash = sha256_file(project_root / state_path) if state_path else None
    layout = {
        "deploymentDirectory": context["deploymentDirectory"],
        "terraformDirectory": context["terraformDirectory"],
        "backendMode": context["backendMode"],
        "stateFile": state_path,
    }
    return {
        "schemaVersion": 1,
        "deploymentId": context["deploymentId"],
        "deploymentDirectory": context["deploymentDirectory"],
        "ruleRevision": 1,
        "resolverVersion": "2.0.0",
        "terraform": {
            "workingDirectory": context["terraformDirectory"],
            "backendMode": context["backendMode"],
            "backendConfigFile": state_path if context["backendMode"] == "oss" else None,
            "localStateFile": state_path if context["backendMode"] == "local" else None,
            "workspace": "default",
        },
        "application": {"protectedEnvFile": context["protectedEnvFile"]},
        "metadataBindings": {
            "deploymentId": "terraform-variables:deployment_id",
            "environment": "terraform-variables:environment",
            "region": "terraform-variables:region",
            "repositoryUrl": "deployment-metadata:repositoryUrl",
            "activeCommit": "ecs-runtime",
        },
        "outputBindings": {
            "region": "region", "vpcId": "vpc_id", "vswitchIds": "vswitch_ids",
            "ecsInstanceIds": "ecs_instance_ids", "ecsPrivateIps": "ecs_private_ips",
            "albId": "alb_id", "albAddress": "alb_dns_name", "rds": "rds",
            "redis": "redis", "oss": "oss", "sls": "sls", "expectedTags": "expected_tags",
        },
        "validation": {
            "backendConfigSha256": state_hash,
            "layoutFingerprint": sha256_bytes(canonical_json(layout)),
            "lastValidatedAt": utc_now(),
        },
    }


def build_manifest(context: Dict[str, Any], inventory: Dict[str, Any]) -> Dict[str, Any]:
    resources = dict(context["resources"])
    commit = context["activeCommit"]
    return {
        "schemaVersion": 1,
        "mode": "upgrade",
        "phase": "upgrade-discovery",
        "status": "accepted",
        "cloudProfile": context["cloudProfile"],
        "localContext": {
            "sourceDirectory": ".",
            "terraformDirectory": context["terraformDirectory"],
            "protectedEnvFile": context["protectedEnvFile"],
        },
        "region": context["region"],
        "environment": context["environment"],
        "deploymentId": context["deploymentId"],
        "topology": context["tags"]["Topology"],
        "repositoryUrl": context["repositoryUrl"],
        "repositoryRef": "master",
        "repositoryCommit": commit or "",
        **(
            {"recommendedRuntimeVersion": context["recommendedRuntimeVersion"]}
            if context.get("recommendedRuntimeVersion")
            else {}
        ),
        "tags": context["tags"],
        "terraform": {
            "stateMode": context["backendMode"],
            "stateReference": context["stateFile"],
        },
        "resources": resources,
        "deployment": {"activeCommit": commit or "", "acceptedCommit": commit or ""},
        "scaling": {"pendingInstanceIds": []},
        "upgradeInfo": {
            "resourceSetFingerprint": inventory["resourceSetFingerprint"],
            "discoveryRuleRevision": 1,
        },
        "upgrade": {},
    }


def write_upgrade_info(project_root: Path, context: Dict[str, Any]) -> Path:
    info_root = project_root / "upgrade-info"
    if info_root.is_symlink():
        raise UpgradeInfoError("upgrade-info must not be a symbolic link")
    deployment_info = info_root / context["deploymentId"]
    if deployment_info.exists():
        raise UpgradeInfoError("Upgrade information already exists for this deployment")
    inventory = build_inventory(None, context, context["resources"])
    discovery = build_discovery(project_root, context)
    manifest = build_manifest(context, inventory)
    index = {
        "schemaVersion": 1,
        "activeDeploymentId": context["deploymentId"],
        "deployments": {
            context["deploymentId"]: {
                "deploymentDirectory": (
                    "protected-auto-wonder/" + Path(context["deploymentDirectory"]).name
                    if Path(context["deploymentDirectory"]).is_absolute()
                    else context["deploymentDirectory"]
                ),
                "infoDirectory": "upgrade-info/" + context["deploymentId"],
                "lastUsedAt": utc_now(),
            }
        },
    }
    upgrade_state = {
        "schemaVersion": 1,
        "deploymentId": context["deploymentId"],
        "status": "discovered",
        "sourceCommit": context["activeCommit"],
        "targetCommit": None,
        "resourceSetFingerprint": inventory["resourceSetFingerprint"],
        "planFingerprint": None,
        "migrationStatus": "unknown",
        "rollbackBoundary": {"applicationRollbackAvailable": False, "databaseMigrationApplied": False},
        "latestRunId": None,
    }
    deployment_info.mkdir(parents=True, mode=0o700)
    os.chmod(info_root, 0o700)
    os.chmod(deployment_info, 0o700)
    try:
        atomic_write_json(deployment_info / "discovery.json", discovery)
        atomic_write_json(deployment_info / "inventory.json", inventory)
        atomic_write_json(deployment_info / "manifest.json", manifest)
        atomic_write_json(deployment_info / "upgrade-state.json", upgrade_state)
        atomic_write_json(info_root / "index.json", index)
    except Exception:
        for path in sorted(deployment_info.glob("*"), reverse=True):
            if path.is_file():
                path.unlink()
        if deployment_info.exists():
            deployment_info.rmdir()
        if info_root.exists() and not any(info_root.iterdir()):
            info_root.rmdir()
        raise
    return deployment_info / "manifest.json"


def relative_reference(project_root: Path, value: str, expect_directory: bool) -> str:
    supplied = Path(value).expanduser()
    candidate = supplied if supplied.is_absolute() else project_root / supplied
    try:
        resolved = candidate.resolve(strict=True)
        if not is_protected_deployment_path(resolved):
            resolved.relative_to(project_root)
    except (OSError, ValueError) as exc:
        raise UpgradeInfoError("Deployment reference must stay inside the project root or protected AutoWonder deployment root") from exc
    if resolved.is_symlink():
        raise UpgradeInfoError("Deployment reference must not be a symbolic link")
    if expect_directory and not resolved.is_dir():
        raise UpgradeInfoError("Deployment directory reference is invalid")
    if not expect_directory and not resolved.is_file():
        raise UpgradeInfoError("Deployment file reference is invalid")
    if is_protected_deployment_path(resolved):
        return str(resolved)
    return relative_string(project_root, resolved)


def context_from_manifest(project_root: Path, manifest_path: Path) -> Dict[str, Any]:
    source = load_json(manifest_path)
    deployment_id = source.get("deploymentId")
    if not isinstance(deployment_id, str) or not DEPLOYMENT_ID_RE.fullmatch(deployment_id):
        raise UpgradeInfoError("Deployment ID is invalid")
    tags = nested(source, "resources", "expected_tags") or source.get("tags")
    if not isinstance(tags, dict) or any(not tags.get(key) for key in REQUIRED_TAGS):
        raise UpgradeInfoError("Deployment metadata is missing required identity tags")
    terraform_value = nested(source, "localContext", "terraformDirectory")
    protected_value = nested(source, "localContext", "protectedEnvFile")
    state_value = nested(source, "terraform", "stateReference")
    if is_protected_deployment_path(manifest_path):
        terraform_value = terraform_value or str(manifest_path.parent / "terraform")
        protected_value = protected_value or str(manifest_path.parent / "application.env")
        state_value = state_value or str(manifest_path.parent / "backend.hcl")
    if not terraform_value or not protected_value or not state_value:
        raise UpgradeInfoError("Current deployment handoff is missing local Terraform references")
    terraform_directory = relative_reference(project_root, str(terraform_value), True)
    protected_env = relative_reference(project_root, str(protected_value), False)
    state_reference = relative_reference(project_root, str(state_value), False)
    state_mode = source.get("stateMode") or nested(source, "terraform", "stateMode")
    if state_mode not in {"local", "remote", "oss"}:
        state_mode = "local" if state_reference.endswith(".tfstate") else "oss"
    backend_mode = "oss" if state_mode in {"remote", "oss"} else "local"
    resources = sanitize_inventory(source.get("resources") or {})
    normalize_ecs(resources.get("ecs_instance_ids"))
    commit = nested(source, "deployment", "activeCommit") or source.get("repositoryCommit")
    if not isinstance(commit, str) or not COMMIT_RE.fullmatch(commit):
        raise UpgradeInfoError("Deployment active commit is invalid")
    return {
        "deploymentId": deployment_id,
        "environment": source.get("environment"),
        "region": source.get("region"),
        "cloudProfile": source.get("cloudProfile") or "default",
        "repositoryUrl": source.get("repositoryUrl") or "",
        "activeCommit": commit,
        "recommendedRuntimeVersion": source.get("recommendedRuntimeVersion"),
        "tags": {key: tags[key] for key in REQUIRED_TAGS},
        "resources": resources,
        "deploymentDirectory": (
            str(manifest_path.parent.resolve())
            if is_protected_deployment_path(manifest_path)
            else relative_string(project_root, manifest_path.parent)
        ),
        "terraformDirectory": terraform_directory,
        "backendMode": backend_mode,
        "stateFile": state_reference,
        "protectedEnvFile": protected_env,
        "tfvarsFile": None,
        "inventoryFile": None,
    }


def cached_registration(project_root: Path) -> Optional[Dict[str, Any]]:
    index_path = project_root / "upgrade-info" / "index.json"
    if not index_path.is_file() or index_path.is_symlink():
        return None
    index = load_json(index_path)
    deployment_id = index.get("activeDeploymentId")
    entry = nested(index, "deployments", str(deployment_id))
    if not isinstance(entry, dict):
        raise UpgradeInfoError("upgrade-info index has no active deployment")
    info_directory = entry.get("infoDirectory")
    if not isinstance(info_directory, str):
        raise UpgradeInfoError("upgrade-info index is missing its working directory")
    directory = resolve_project_path(project_root, info_directory)
    manifest = directory / "manifest.json"
    if not manifest.is_file() or manifest.is_symlink():
        raise UpgradeInfoError("upgrade-info working manifest is unavailable")
    return {
        "status": "resolved",
        "manifest": str(manifest.resolve()),
        "source": "cache",
        "refreshRequired": True,
        "cloudProfile": load_json(manifest).get("cloudProfile") or "default",
    }


def current_manifest_candidates(project_root: Path) -> List[Path]:
    deployments = project_root / "deployments"
    if not deployments.is_dir() or deployments.is_symlink():
        return []
    complete: List[Path] = []
    candidates = list(deployments.glob("*/deployment-manifest.json"))
    candidates.extend(deployments.glob("*/manifest.json"))
    for path in sorted(candidates):
        if not path.is_file() or path.is_symlink():
            continue
        try:
            value = load_json(path)
        except UpgradeInfoError:
            continue
        commit = nested(value, "deployment", "activeCommit") or value.get("repositoryCommit")
        instances = nested(value, "resources", "ecs_instance_ids") or nested(value, "resources", "ecsInstanceIds")
        if (
            isinstance(instances, dict) and instances
            and isinstance(commit, str) and COMMIT_RE.fullmatch(commit)
        ):
            complete.append(path)
    return complete


def register_manifest(project_root: Path, manifest_path: Path, source: str) -> Dict[str, Any]:
    context = context_from_manifest(project_root, manifest_path)
    info_root = project_root / "upgrade-info"
    info_dir = info_root / context["deploymentId"]
    working_manifest = info_dir / "manifest.json"
    if not info_dir.exists():
        working_manifest = write_upgrade_info(project_root, context)
    else:
        if info_dir.is_symlink() or not working_manifest.is_file():
            raise UpgradeInfoError("Registered upgrade information is invalid")
        discovery = load_json(info_dir / "discovery.json")
        if discovery.get("deploymentId") != context["deploymentId"]:
            raise UpgradeInfoError("Registered upgrade information has a conflicting identity")
        previous = load_json(info_dir / "inventory.json")
        manifest = load_json(working_manifest)
        inventory = build_inventory(previous, context, context["resources"])
        prior_fingerprint = nested(manifest, "upgradeInfo", "resourceSetFingerprint")
        manifest["resources"] = context["resources"]
        manifest["repositoryCommit"] = context["activeCommit"] or ""
        manifest.setdefault("deployment", {})["activeCommit"] = context["activeCommit"] or ""
        manifest.setdefault("upgradeInfo", {}).update({
            "resourceSetFingerprint": inventory["resourceSetFingerprint"],
            "discoveryRuleRevision": discovery.get("ruleRevision", 1),
            "lastRefreshAt": inventory["refreshedAt"],
            "changeType": inventory["change"]["type"],
            "addedEcsInstanceIds": inventory["change"]["addedEcsInstanceIds"],
            "removedEcsInstanceIds": inventory["change"]["removedEcsInstanceIds"],
        })
        if prior_fingerprint and prior_fingerprint != inventory["resourceSetFingerprint"]:
            upgrade = manifest.setdefault("upgrade", {})
            for key in ("approval", "planFingerprint", "rollbackBackup", "targetVerification"):
                upgrade.pop(key, None)
            if isinstance(manifest.get("upgradeInventory"), dict):
                manifest["upgradeInventory"]["status"] = "stale"
        atomic_write_json(info_dir / "inventory.json", inventory)
        atomic_write_json(working_manifest, manifest)
        index_path = info_root / "index.json"
        index = load_json(index_path)
        index["activeDeploymentId"] = context["deploymentId"]
        index.setdefault("deployments", {}).setdefault(context["deploymentId"], {}).update({
            "deploymentDirectory": discovery.get("deploymentDirectory"),
            "infoDirectory": "upgrade-info/" + context["deploymentId"],
            "lastUsedAt": utc_now(),
        })
        atomic_write_json(index_path, index)
    return {
        "status": "resolved",
        "manifest": str(working_manifest.resolve()),
        "source": source,
        "refreshRequired": True,
        "cloudProfile": context["cloudProfile"],
    }


def register_manifest_command(args: argparse.Namespace) -> Dict[str, Any]:
    project_root = Path(args.project_root).expanduser().resolve(strict=True)
    manifest_path = resolve_supplied_file(project_root, args.manifest)
    return register_manifest(project_root, manifest_path, "deployment-handoff")


def sync_manifest(args: argparse.Namespace) -> Dict[str, Any]:
    project_root = Path(args.project_root).expanduser().resolve(strict=True)
    manifest_path = require_working_manifest(project_root, args.manifest)
    manifest = load_json(manifest_path)
    info_dir = manifest_path.parent
    fingerprint = nested(manifest, "upgradeInfo", "resourceSetFingerprint")
    if not isinstance(fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
        raise UpgradeInfoError("Working manifest has no resource set fingerprint")
    upgrade = manifest.get("upgrade") if isinstance(manifest.get("upgrade"), dict) else {}
    migration = upgrade.get("databaseMigration") if isinstance(upgrade.get("databaseMigration"), dict) else {}
    applied = migration.get("applied") if isinstance(migration.get("applied"), list) else []
    migration_status = migration.get("status") or "unknown"
    rollback = upgrade.get("rollbackBackup") if isinstance(upgrade.get("rollbackBackup"), dict) else {}
    rollback_available = rollback.get("status") == "passed" and not applied
    source_commit = upgrade.get("fromCommit") or nested(manifest, "deployment", "activeCommit") or None
    target_commit = upgrade.get("toCommit") or manifest.get("repositoryCommit") or None
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ-") + fingerprint[:12]
    state = {
        "schemaVersion": 1,
        "deploymentId": manifest.get("deploymentId"),
        "status": manifest.get("status") or "unknown",
        "sourceCommit": source_commit,
        "targetCommit": target_commit,
        "resourceSetFingerprint": fingerprint,
        "planFingerprint": upgrade.get("planFingerprint"),
        "migrationStatus": migration_status,
        "rollbackBoundary": {
            "applicationRollbackAvailable": rollback_available,
            "databaseMigrationApplied": bool(applied),
        },
        "acceptedAt": utc_now() if manifest.get("status") == "accepted" else None,
        "latestRunId": run_id,
    }
    rolling = manifest.get("rollingUpgrade") if isinstance(manifest.get("rollingUpgrade"), dict) else {}
    nodes = rolling.get("nodes") if isinstance(rolling.get("nodes"), list) else []
    safe_nodes = [
        {"instanceId": node.get("instanceId"), "status": node.get("status")}
        for node in nodes if isinstance(node, dict) and node.get("instanceId")
    ]
    summary = {
        "schemaVersion": 1,
        "runId": run_id,
        "operation": args.operation,
        "sourceCommit": source_commit,
        "targetCommit": target_commit,
        "resourceSetFingerprint": fingerprint,
        "nodeCount": len(nested(manifest, "resources", "ecs_instance_ids") or nested(manifest, "resources", "ecsInstanceIds") or {}),
        "migrationStatus": migration_status,
        "nodes": safe_nodes,
        "acceptance": manifest.get("acceptance") if isinstance(manifest.get("acceptance"), dict) else {},
        "rollbackBoundary": state["rollbackBoundary"],
        "completedAt": utc_now(),
    }
    atomic_write_json(info_dir / "upgrade-state.json", state)
    atomic_write_json(info_dir / "runs" / run_id / "summary.json", summary)
    return {"status": "synced", "runId": run_id, "manifest": str(manifest_path)}


def locate(args: argparse.Namespace) -> Dict[str, Any]:
    project_root = Path(args.project_root).expanduser().resolve(strict=True)
    if not project_root.is_dir():
        raise UpgradeInfoError("Project root was not found")
    if args.manifest:
        manifest_path = resolve_supplied_file(project_root, args.manifest)
        try:
            manifest_parts = manifest_path.relative_to(project_root).parts
        except ValueError:
            manifest_parts = ()
        if "upgrade-info" in manifest_parts:
            return {
                "status": "resolved", "manifest": str(manifest_path), "source": "explicit",
                "refreshRequired": True, "cloudProfile": load_json(manifest_path).get("cloudProfile") or "default",
            }
        return register_manifest(project_root, manifest_path, "explicit-manifest")
    cached = cached_registration(project_root)
    if cached:
        return cached
    candidates = current_manifest_candidates(project_root)
    if len(candidates) > 1:
        raise MultipleDeployments("Found multiple complete deployment manifests; provide one project-root folder")
    if len(candidates) == 1:
        return register_manifest(project_root, candidates[0], "current-manifest")
    if not args.deployment_dir:
        raise DeploymentFolderRequired("Deployment folder is required")
    deployment_dir = resolve_project_path(project_root, args.deployment_dir)
    context = extract_context(project_root, deployment_dir)
    manifest = write_upgrade_info(project_root, context)
    return {
        "status": "located", "manifest": str(manifest.resolve()), "source": "registered",
        "refreshRequired": True, "cloudProfile": context["cloudProfile"],
    }


def require_working_manifest(project_root: Path, value: str) -> Path:
    supplied = Path(value).expanduser()
    try:
        manifest = supplied.resolve(strict=True)
        relative = manifest.relative_to(project_root)
    except (OSError, ValueError) as exc:
        raise UpgradeInfoError("Working manifest must stay inside the project root") from exc
    if manifest.is_symlink() or not manifest.is_file():
        raise UpgradeInfoError("Working manifest is unavailable")
    parts = relative.parts
    if len(parts) != 3 or parts[0] != "upgrade-info" or parts[2] != "manifest.json":
        raise UpgradeInfoError("Working manifest must use the upgrade-info layout")
    return manifest


def refresh(args: argparse.Namespace) -> Dict[str, Any]:
    project_root = Path(args.project_root).expanduser().resolve(strict=True)
    manifest_path = require_working_manifest(project_root, args.manifest)
    info_dir = manifest_path.parent
    discovery_path = info_dir / "discovery.json"
    inventory_path = info_dir / "inventory.json"
    discovery = load_json(discovery_path)
    manifest = load_json(manifest_path)
    previous = load_json(inventory_path) if inventory_path.is_file() else None
    resources = run_terraform_output(project_root, discovery)
    context = {
        "deploymentId": manifest.get("deploymentId"),
        "region": resources.get("region"),
        "backendMode": nested(discovery, "terraform", "backendMode"),
        "terraformDirectory": nested(discovery, "terraform", "workingDirectory"),
    }
    if context["deploymentId"] != discovery.get("deploymentId"):
        raise UpgradeInfoError("Discovery record does not match the working manifest")
    if manifest.get("region") != context["region"]:
        raise UpgradeInfoError("Terraform region does not match the registered deployment")
    if not manifest.get("recommendedRuntimeVersion"):
        deployment_directory = resolve_project_path(
            project_root, str(discovery.get("deploymentDirectory") or "")
        )
        candidates = deployment_metadata_candidates(deployment_directory)
        recommended_runtime_version = unique_value(
            "recommended runtime version",
            [item.get("recommendedRuntimeVersion") for item in candidates],
            required=False,
        )
        if recommended_runtime_version is not None and (
            not isinstance(recommended_runtime_version, str)
            or not SEMANTIC_VERSION_RE.fullmatch(recommended_runtime_version)
        ):
            raise UpgradeInfoError("Recommended runtime version is invalid")
        if recommended_runtime_version:
            manifest["recommendedRuntimeVersion"] = recommended_runtime_version
    expected_tags = resources.get("expected_tags")
    if isinstance(expected_tags, dict):
        if (
            expected_tags.get("DeploymentId") != manifest.get("deploymentId")
            or expected_tags.get("Environment") != manifest.get("environment")
        ):
            raise UpgradeInfoError("Terraform identity does not match the registered deployment")
    inventory = build_inventory(previous, context, resources)
    prior_fingerprint = nested(manifest, "upgradeInfo", "resourceSetFingerprint")
    current_fingerprint = inventory["resourceSetFingerprint"]
    manifest["resources"] = resources
    manifest.setdefault("upgradeInfo", {})
    manifest["upgradeInfo"].update({
        "resourceSetFingerprint": current_fingerprint,
        "discoveryRuleRevision": discovery.get("ruleRevision", 1),
        "lastRefreshAt": inventory["refreshedAt"],
        "changeType": inventory["change"]["type"],
        "addedEcsInstanceIds": inventory["change"]["addedEcsInstanceIds"],
        "removedEcsInstanceIds": inventory["change"]["removedEcsInstanceIds"],
    })
    if prior_fingerprint and prior_fingerprint != current_fingerprint:
        upgrade = manifest.setdefault("upgrade", {})
        for key in ("approval", "planFingerprint", "rollbackBackup", "targetVerification"):
            upgrade.pop(key, None)
        if isinstance(manifest.get("upgradeInventory"), dict):
            manifest["upgradeInventory"]["status"] = "stale"
    validation = discovery.setdefault("validation", {})
    backend_reference = nested(discovery, "terraform", "backendConfigFile")
    if backend_reference:
        validation["backendConfigSha256"] = sha256_file(
            resolve_project_path_file(project_root, backend_reference)
        )
    validation["lastValidatedAt"] = utc_now()
    atomic_write_json(inventory_path, inventory)
    atomic_write_json(discovery_path, discovery)
    atomic_write_json(manifest_path, manifest)
    return {
        "status": "refreshed",
        "manifest": str(manifest_path),
        "deploymentId": manifest["deploymentId"],
        "nodeCount": inventory["nodeCount"]["current"],
        "changeType": inventory["change"]["type"],
        "resourceSetFingerprint": current_fingerprint,
    }


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    subparsers = result.add_subparsers(dest="command", required=True)
    locate_parser = subparsers.add_parser("locate")
    locate_parser.add_argument("--project-root", required=True)
    locate_parser.add_argument("--deployment-dir")
    locate_parser.add_argument("--manifest")
    refresh_parser = subparsers.add_parser("refresh")
    refresh_parser.add_argument("--project-root", required=True)
    refresh_parser.add_argument("--manifest", required=True)
    register_parser = subparsers.add_parser("register-manifest")
    register_parser.add_argument("--project-root", required=True)
    register_parser.add_argument("--manifest", required=True)
    sync_parser = subparsers.add_parser("sync-manifest")
    sync_parser.add_argument("--project-root", required=True)
    sync_parser.add_argument("--manifest", required=True)
    sync_parser.add_argument("--operation", required=True)
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "locate":
            result = locate(args)
        elif args.command == "refresh":
            result = refresh(args)
        elif args.command == "register-manifest":
            result = register_manifest_command(args)
        elif args.command == "sync-manifest":
            result = sync_manifest(args)
        else:
            raise UpgradeInfoError("Unsupported upgrade-info command")
    except DeploymentFolderRequired:
        print(json.dumps({"status": "deployment-folder-required"}, separators=(",", ":")))
        return 5
    except MultipleDeployments as exc:
        print("Upgrade information discovery failed: " + str(exc), file=sys.stderr)
        return 3
    except (UpgradeInfoError, OSError) as exc:
        print("Upgrade information discovery failed: " + str(exc), file=sys.stderr)
        return 6
    print(json.dumps(result, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
