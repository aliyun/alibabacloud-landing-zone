#!/usr/bin/env python3
"""Produce the complete portable release descriptor used by the Windows adapter."""
import argparse
import json
from pathlib import Path
import re
import sys


def release_fields(build, source):
    version = (Path(source) / 'VERSION').read_text(encoding='utf-8-sig').strip()
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?', version):
        raise ValueError('VERSION must contain a semantic version')
    names = {'jar': 'auto-wonder.jar', 'schema': 'autowonder-schema.sql',
             'templates': 'autowonder-community-templates.sql',
             'migrations': 'autowonder-migrations.tar.gz', 'systemdUnit': 'autowonder.service'}
    artifacts = {'releaseDirectory': build['directory']}
    for key, name in names.items():
        evidence = build['artifacts'][name]
        artifacts[key] = dict(name=name, **evidence)
    return {'releaseVersion': version, 'artifacts': artifacts}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', required=True)
    args = parser.parse_args()
    print(json.dumps(release_fields(json.loads(sys.stdin.buffer.read().decode('utf-8-sig')), args.source_dir)))
