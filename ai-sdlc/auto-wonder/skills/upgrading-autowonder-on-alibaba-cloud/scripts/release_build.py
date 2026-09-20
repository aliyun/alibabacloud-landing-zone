#!/usr/bin/env python3
"""Shared pure release build; approval, identity and manifest sealing stay in callers."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile


def validate_jar(path):
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if 'BOOT-INF/classes/static/index.html' not in names:
                raise ValueError('release JAR is missing frontend static/index.html')
            if not any(name.startswith('BOOT-INF/classes/static/assets/') and not name.endswith('/') for name in names):
                raise ValueError('release JAR is missing frontend static assets')
            if archive.testzip() is not None:
                raise ValueError('release JAR contains corrupt entries')
    except zipfile.BadZipFile as error:
        raise ValueError('release JAR is not a valid archive') from error


def package_migrations(directory, destination):
    directory = Path(directory)
    if not directory.is_dir() or directory.is_symlink():
        raise ValueError('migration directory is missing or unsafe')
    entries = sorted(directory.rglob('*'))
    if any(entry.is_symlink() or not (entry.is_file() or entry.is_dir()) for entry in entries):
        raise ValueError('migration archive cannot contain links or special files')
    # Match remote extraction: tar -xzf archive -C RELEASE/migration.
    # Python tarfile never synthesizes macOS AppleDouble/xattr files.
    with tarfile.open(destination, 'w:gz') as archive:
        archive.add(directory, arcname='.', recursive=False)
        for entry in entries:
            archive.add(entry, arcname='./' + entry.relative_to(directory).as_posix(), recursive=False)


def file_evidence(path):
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256')
    return {'sha256': digest.hexdigest(), 'size': path.stat().st_size, 'source': 'target-source'}


def build_release(source, output, include_unit=False, maven='mvn'):
    source, output = Path(source).resolve(), Path(output).resolve()
    maven = shutil.which(maven) or maven
    result = subprocess.run([maven, '-DskipGitCommitId=true', '-DskipFrontend=false', '-Dmaven.test.skip=true', 'clean', 'package'],
                            cwd=source, stdout=sys.stderr, check=False)
    if result.returncode:
        raise ValueError('release Maven clean package failed')
    files = {'auto-wonder.jar': source / 'target/auto-wonder.jar',
             'autowonder-schema.sql': source / 'docs/autowonder-schema.sql',
             'autowonder-community-templates.sql': source / 'docs/autowonder-community-templates.sql'}
    if include_unit:
        files['autowonder.service'] = source / 'skills/upgrading-autowonder-on-alibaba-cloud/assets/systemd/autowonder.service'
    for name, path in files.items():
        if not path.is_file():
            raise ValueError('release artifact is missing: ' + name)
    validate_jar(files['auto-wonder.jar'])
    output.mkdir(parents=True, exist_ok=True)
    if os.name != 'nt':
        output.chmod(0o700)
    with tempfile.TemporaryDirectory(prefix='.release-tmp-', dir=output) as staging_name:
        staging = Path(staging_name)
        for name, path in files.items():
            shutil.copyfile(path, staging / name)
        package_migrations(source / 'docs/migration', staging / 'autowonder-migrations.tar.gz')
        artifacts = {}
        for path in sorted(staging.iterdir()):
            artifacts[path.name] = file_evidence(path)
            destination = output / path.name
            # Windows cannot replace an existing read-only destination. Do not
            # truncate it; replacement remains atomic and failure restores mode.
            old_mode = destination.stat().st_mode if destination.exists() else None
            if os.name == 'nt' and old_mode is not None:
                destination.chmod(0o600)
            try:
                os.replace(path, destination)
            except OSError:
                if old_mode is not None and destination.exists():
                    destination.chmod(old_mode)
                raise
            destination.chmod(0o444)
    return {'directory': str(output), 'artifacts': artifacts}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', required=True)
    parser.add_argument('--output-dir', required=True)
    parser.add_argument('--include-unit', action='store_true')
    args = parser.parse_args(argv)
    started = time.monotonic()
    try:
        result = build_release(args.source_dir, args.output_dir, args.include_unit)
    except (ValueError, OSError, tarfile.TarError) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        return 1
    print(f'INFO: release build completed in {time.monotonic() - started:.2f}s', file=sys.stderr)
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
