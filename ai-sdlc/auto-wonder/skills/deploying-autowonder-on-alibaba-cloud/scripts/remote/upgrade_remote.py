"""Read-only Linux inventory payload for legacy deployment recovery.

Only the base64 request is substituted by the control host. Never emit the
request, credentials, signed URLs, SQL content, or raw exception details.
"""
import base64
import hashlib
import json
from pathlib import Path
import re
import sys


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


try:
    request = json.loads(base64.b64decode("@@REQUEST@@"))
    require(request["operation"] == "upgrade-inventory", "Only upgrade-inventory is supported by the deployment Skill")
    require(re.fullmatch(r"[0-9a-f]{12}|[0-9a-f]{40}", request["from"]) is not None, "Invalid source release identity")
    app = Path("/opt/autowonder").resolve()
    active = (app / "current").resolve()
    require(active.parent == app / "releases" and active.name == request["from"][:12], "Active release inventory mismatch")
    require((active / "auto-wonder.jar").is_file(), "Active release JAR is missing")
    require((active / "autowonder-migrations.tar.gz").is_file(), "Active release migration archive is missing")
    print("ACTIVE_COMMIT=" + request["from"])
    print("JAR_SHA256=" + digest(active / "auto-wonder.jar"))
    print("MIGRATIONS_SHA256=" + digest(active / "autowonder-migrations.tar.gz"))
except RuntimeError as error:
    print(str(error), file=sys.stderr)
    sys.exit(1)
except Exception:
    print("Remote inventory failed; inspect the sanitized operation checkpoint", file=sys.stderr)
    sys.exit(1)
