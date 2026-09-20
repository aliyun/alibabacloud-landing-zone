"""Linux payload submitted by the native Windows upgrade adapter.

Only the base64 request is substituted by the control host. Never emit the request,
credentials, signed URLs, SQL content, or raw subprocess errors.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import pwd
import grp
import re
import select
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
from urllib.parse import urlsplit

REQUEST = json.loads(base64.b64decode("@@REQUEST@@"))
APP = Path("/opt/autowonder").resolve()
ENV = Path("/etc/autowonder/autowonder.env")
UNIT = Path("/etc/systemd/system/autowonder.service")
BACKUP = APP / "upgrade-rollback-backup.tar.gz"
os.umask(0o077)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def digest(path):
    result = hashlib.sha256()
    with open(str(path), "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def command(arguments, **kwargs):
    result = subprocess.run(arguments, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, **kwargs)
    require(result.returncode == 0, "Remote upgrade command failed")
    return result.stdout


def extract(archive, destination, prefix=None):
    with tarfile.open(str(archive), "r:gz") as package:
        members = package.getmembers()
        # POSIX seals the directory contents; Windows seals migration/ itself.
        if prefix and not any(Path(member.name).parts[:1] == (prefix,) for member in members):
            destination = destination / prefix
            prefix = None
        for member in members:
            name = Path(member.name)
            require(not name.is_absolute() and ".." not in name.parts, "Unsafe archive member")
            if prefix:
                require(name.parts and name.parts[0] == prefix, "Unexpected migration archive member")
            require(member.isfile() or member.isdir(), "Archive links are not allowed")
            target = destination / name
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with package.extractfile(member) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
                target.chmod(member.mode & 0o777)


def verify_backup(destination, expected_sha=None):
    if expected_sha:
        require(digest(BACKUP) == expected_sha, "Backup archive checksum mismatch")
    extract(BACKUP, destination)
    metadata = json.loads((destination / "metadata.json").read_text())
    require(metadata["plan"] == REQUEST["plan"] and metadata["target"] == REQUEST["target"], "Backup belongs to a different plan")
    require(metadata["release"] == REQUEST["from"][:12], "Backup source release mismatch")
    for name, sha in metadata["checksums"].items():
        require(digest(destination / name) == sha, "Backup content checksum mismatch")
    return metadata


def backup():
    with tempfile.TemporaryDirectory(prefix=".upgrade-backup-", dir=str(APP)) as temporary:
        work = Path(temporary)
        if BACKUP.exists():
            # Repeating backup after stage must not overwrite the original environment.
            extract(BACKUP, work / "existing")
            existing = json.loads((work / "existing/metadata.json").read_text())
            if existing["plan"] == REQUEST["plan"]:
                verify_backup(work / "verified")
                print("BACKUP_SHA256=" + digest(BACKUP))
                return
        active = (APP / "current").resolve()
        require(active.parent == APP / "releases" and active.name == REQUEST["from"][:12], "Active release differs from approved source")
        snapshot = work / "snapshot"
        snapshot.mkdir()
        require(not any(path.is_symlink() for path in active.rglob("*")), "Active release contains unsupported links")
        shutil.copytree(str(active), str(snapshot / "release"))
        shutil.copyfile(str(ENV), str(snapshot / "autowonder.env"))
        shutil.copyfile(str(UNIT), str(snapshot / "autowonder.service"))
        metadata = {"plan": REQUEST["plan"], "target": REQUEST["target"], "release": active.name,
                    "checksums": {str(path.relative_to(snapshot)): digest(path) for path in snapshot.rglob("*") if path.is_file()}}
        (snapshot / "metadata.json").write_text(json.dumps(metadata, sort_keys=True))
        archive = work / "backup.tar.gz"
        with tarfile.open(str(archive), "w:gz") as package:
            for path in sorted(snapshot.iterdir()):
                package.add(str(path), arcname=path.name)
        verified = work / "verify"
        extract(archive, verified)
        for name, sha in metadata["checksums"].items():
            require(digest(verified / name) == sha, "Replacement backup verification failed")
        archive.chmod(0o600)
        os.replace(str(archive), str(BACKUP))
        print("BACKUP_SHA256=" + digest(BACKUP))


def install_file(source, target, mode, group):
    temporary = target.with_name(target.name + ".upgrade-tmp")
    try:
        shutil.copyfile(str(source), str(temporary))
        os.chown(str(temporary), pwd.getpwnam("root").pw_uid, grp.getgrnam(group).gr_gid)
        temporary.chmod(mode)
        os.replace(str(temporary), str(target))
    finally:
        if temporary.exists():
            temporary.unlink()


def stage():
    with tempfile.TemporaryDirectory(prefix=".upgrade-stage-", dir=str(APP)) as temporary:
        work = Path(temporary)
        verify_backup(work / "backup", REQUEST.get("backupSha"))
        release = work / "release"
        release.mkdir()
        expected = {"auto-wonder.jar", "autowonder-schema.sql", "autowonder-community-templates.sql", "autowonder-migrations.tar.gz", "autowonder.service", "autowonder.env"}
        require({item["name"] for item in REQUEST["objects"]} == expected and len(REQUEST["objects"]) == len(expected), "Incomplete release object set")
        for item in REQUEST["objects"]:
            path = release / item["name"]
            require(re.fullmatch(r"[0-9a-f]{64}", item["sha256"]) is not None, "Invalid artifact checksum")
            command(["curl", "--fail", "--silent", "--connect-timeout", "10", "--max-time", "900", item["url"], "-o", str(path)])
            require(digest(path) == item["sha256"], "Downloaded artifact checksum mismatch")
        candidate = release / "autowonder.env"
        verify_environment(candidate)
        extract(release / "autowonder-migrations.tar.gz", release, "migration")
        require((release / "migration").is_dir(), "Migration archive is incomplete")
        candidate.chmod(0o600)
        protected_candidate = work / "autowonder.env"
        os.replace(str(candidate), str(protected_candidate))
        (release / "release-identity.json").write_text(json.dumps({"commit": REQUEST["target"], "plan": REQUEST["plan"]}))
        for path in release.rglob("*"):
            path.chmod(0o555 if path.is_dir() else 0o444)
        release.chmod(0o755)
        target = APP / "releases" / REQUEST["target"][:12]
        if target.exists():
            for item in REQUEST["objects"]:
                if item["name"] == "autowonder.service" and not (target / item["name"]).exists():
                    continue  # POSIX staging installs the unit outside the release.
                if item["name"] != "autowonder.env":
                    require(digest(target / item["name"]) == item["sha256"], "Existing immutable release differs from target")
            if not (target / "autowonder.service").exists():
                install_file(release / "autowonder.service", target / "autowonder.service", 0o444, "root")
        else:
            os.replace(str(release), str(target))
        # Only install configuration after every transfer and checksum has passed.
        install_file(protected_candidate, ENV, 0o640, "autowonder")
        verify_environment(ENV)
        install_file(target / "autowonder.service", UNIT, 0o644, "root")
        print("STAGED_COMMIT=" + REQUEST["target"])


def read_environment(path):
    values = {}
    for raw in path.read_text(encoding="utf-8-sig").splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        require(re.fullmatch(r"[A-Z][A-Z0-9_]*=.*", raw) is not None, "Invalid environment file syntax")
        key, value = raw.split("=", 1)
        require(key not in values, "Duplicate environment key")
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
    return values


def verify_environment(path):
    require(digest(path) == REQUEST["envSha"], "Installed environment checkpoint mismatch")
    values = read_environment(path)
    require(values.get("AUTOWONDER_RUNTIME_RECOMMENDED_VERSION") == REQUEST["runtime"], "Environment runtime checkpoint mismatch")
    generation = REQUEST.get("keyGenerationId", "")
    require(re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", generation) is not None,
            "Escrow key generation ID must be an opaque UUIDv4")
    require(values.get("AUTOWONDER_SECRET_KEY_GENERATION_ID") == generation, "Environment generation checkpoint mismatch")


def migrate():
    migrations = sorted(REQUEST["migrations"], key=lambda item: item["version"])
    release = APP / "releases" / REQUEST["target"][:12] / "migration"
    seen = set()
    for migration in migrations:
        version, filename = migration["version"], migration["file"]
        require(isinstance(version, int) and version > 0 and version not in seen, "Invalid or duplicate migration version")
        require(re.fullmatch(r"docs/migration/V0*[1-9][0-9]*__[a-z0-9]+(?:_[a-z0-9]+)*\.sql", filename) is not None, "Invalid migration filename")
        require(int(Path(filename).name.split("__", 1)[0][1:]) == version, "Migration filename version mismatch")
        require(digest(release / Path(filename).name) == migration["sha256"], "Migration checksum mismatch")
        seen.add(version)
    values = read_environment(ENV)
    url = values["SPRING_DATASOURCE_URL"]
    require(url.startswith("jdbc:mysql://"), "Unsupported database connection")
    connection = urlsplit(url[5:])
    database = connection.path.lstrip("/")
    require(re.fullmatch(r"[A-Za-z0-9_]+", database) is not None, "Invalid database name")
    environment = dict(os.environ, MYSQL_PWD=values["SPRING_DATASOURCE_PASSWORD"])
    mysql = ["mysql", "-h", connection.hostname, "-P", str(connection.port or 3306), "-u", values["SPRING_DATASOURCE_USERNAME"], "--batch", "--skip-column-names", database]

    def sql(statement):
        return command(mysql + ["-e", statement], env=environment).decode().strip()

    lock = subprocess.Popen(mysql + ["--unbuffered", "--skip-reconnect"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=environment, universal_newlines=True, bufsize=1)
    try:
        lock.stdin.write("SELECT GET_LOCK('autowonder-community-migration', 30);\n")
        lock.stdin.flush()
        readable, _, _ = select.select([lock.stdout], [], [], 35)
        require(readable and lock.stdout.readline().strip() == "1", "Migration lock unavailable")
        sql("CREATE TABLE IF NOT EXISTS autowonder_schema_history (migration_version BIGINT NOT NULL PRIMARY KEY,filename VARCHAR(255) NOT NULL,checksum CHAR(64) NOT NULL,source_commit CHAR(40) NOT NULL,installed_on TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),execution_ms BIGINT NULL,success TINYINT(1) NOT NULL,error_message VARCHAR(512) NULL)")
        for migration in migrations:
            require(lock.poll() is None, "Migration lock connection was lost")
            version, sha, filename = migration["version"], migration["sha256"], Path(migration["file"]).name
            existing = sql("SELECT CONCAT(checksum, ' ', success) FROM autowonder_schema_history WHERE migration_version=%d" % version)
            if existing:
                require(existing == sha + " 1", "Previous failed migration or ledger checksum mismatch requires review")
                continue
            if filename.endswith("__platform_admin_init.sql"):
                admin_count = sql("SELECT COUNT(*) FROM `user` WHERE is_deleted = 0 AND is_admin = 1")
                require(admin_count.isdigit() and int(admin_count) > 0,
                        "Migration requires an existing system administrator; explicit recovery is required")
            # Mark running before executing SQL; interruption remains a failed record.
            sql("INSERT INTO autowonder_schema_history(migration_version,filename,checksum,source_commit,success,error_message) VALUES(%d,'%s','%s','%s',0,'migration started; completion unverified')" % (version, filename, sha, REQUEST["target"]))
            started = time.time()
            with (release / filename).open("rb") as source:
                command(mysql, stdin=source, env=environment)
            require(lock.poll() is None, "Migration lock connection was lost")
            sql("UPDATE autowonder_schema_history SET success=1,error_message=NULL,execution_ms=%d WHERE migration_version=%d AND checksum='%s'" % (int((time.time() - started) * 1000), version, sha))
        print("MIGRATIONS_APPLIED=" + str(len(migrations)))
    finally:
        if lock.poll() is None:
            try:
                lock.stdin.write("SELECT RELEASE_LOCK('autowonder-community-migration');\nquit\n")
                lock.stdin.flush()
                lock.communicate(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                lock.kill()
                lock.communicate()


def health():
    for _ in range(90):
        active = subprocess.run(["systemctl", "is-active", "--quiet", "autowonder.service"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
        try:
            listener = command(["ss", "-ltnH", "sport = :7001"])
            preload = command(["curl", "--fail", "--silent", "--connect-timeout", "2", "--max-time", "5", "http://127.0.0.1:7001/checkpreload.htm"])
            command(["curl", "--fail", "--silent", "--max-time", "5", "http://127.0.0.1:7001/api/platform/branding/public"])
            if active and listener.strip() and preload.strip() == b"success":
                return
        except RuntimeError:
            pass
        time.sleep(2)
    raise RuntimeError("ECS-local acceptance failed")


def activate(target):
    temporary = APP / "current.new"
    if temporary.is_symlink():
        temporary.unlink()
    temporary.symlink_to(target)
    os.replace(str(temporary), str(APP / "current"))
    command(["systemctl", "daemon-reload"])
    command(["systemctl", "restart", "autowonder.service"])
    health()


def rollout():
    with tempfile.TemporaryDirectory(prefix=".upgrade-backup-check-", dir=str(APP)) as temporary:
        verify_backup(Path(temporary), REQUEST["backupSha"])
    target = APP / "releases" / REQUEST["target"][:12]
    require(digest(target / "auto-wonder.jar") == REQUEST["jarSha"], "Target JAR checksum mismatch")
    verify_environment(ENV)
    require(digest(UNIT) == REQUEST["unitSha"], "Staged systemd unit changed before activation")
    activate(target)
    verify_environment(ENV)
    print("ROLLOUT_COMMIT=" + REQUEST["target"])


def rollback():
    with tempfile.TemporaryDirectory(prefix=".upgrade-restore-", dir=str(APP)) as temporary:
        work = Path(temporary)
        metadata = verify_backup(work, REQUEST["backupSha"])
        target = APP / "releases" / metadata["release"]
        if target.exists():
            for name, sha in metadata["checksums"].items():
                if name.startswith("release/"):
                    require(digest(target / name[len("release/"):]) == sha, "Existing rollback release has changed")
        else:
            os.replace(str(work / "release"), str(target))
        install_file(work / "autowonder.env", ENV, 0o640, "autowonder")
        install_file(work / "autowonder.service", UNIT, 0o644, "root")
        activate(target)
        print("ROLLBACK_RELEASE=" + metadata["release"])


def inventory():
    active = (APP / "current").resolve()
    require(active.parent == APP / "releases" and active.name == REQUEST["from"][:12], "Active release inventory mismatch")
    require((active / "auto-wonder.jar").is_file(), "Active release JAR is missing")
    require((active / "autowonder-migrations.tar.gz").is_file(), "Active release migration archive is missing")
    print("ACTIVE_COMMIT=" + REQUEST["from"])
    print("JAR_SHA256=" + digest(active / "auto-wonder.jar"))
    print("MIGRATIONS_SHA256=" + digest(active / "autowonder-migrations.tar.gz"))


try:
    require(re.fullmatch(r"[0-9a-f]{12}|[0-9a-f]{40}", REQUEST["from"]) is not None, "Invalid source release identity")
    if REQUEST["operation"] != "upgrade-inventory":
        require(re.fullmatch(r"[0-9a-f]{40}", REQUEST["target"]) is not None, "Invalid target commit")
        require(re.fullmatch(r"[0-9a-f]{64}", REQUEST["plan"]) is not None, "Invalid plan fingerprint")
    {"upgrade-inventory": inventory, "upgrade-backup": backup, "stage-upgrade": stage,
     "database-migrate": migrate, "rolling-upgrade": rollout, "rollback-upgrade": rollback}[REQUEST["operation"]]()
except RuntimeError as error:
    print(str(error), file=sys.stderr)
    sys.exit(1)
except Exception:
    print("Remote upgrade failed; inspect the sanitized operation checkpoint", file=sys.stderr)
    sys.exit(1)
