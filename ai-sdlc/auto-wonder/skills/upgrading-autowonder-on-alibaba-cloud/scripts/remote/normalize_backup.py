"""Add POSIX reader metadata to an extracted historical Windows snapshot only.

The original archive is never modified, so its recorded checkpoint stays valid.
"""
import hashlib
import json
from pathlib import Path
import re
import sys


def normalize(root):
    if (root / 'CHECKSUMS').is_file():
        return
    metadata = json.loads((root / 'metadata.json').read_text())
    if not re.fullmatch(r'[0-9a-f]{64}', metadata['plan']) or not re.fullmatch(r'[0-9a-f]{12}', metadata['release']):
        raise ValueError('Invalid backup identity')
    checksums = metadata['checksums']
    if not {'autowonder.env', 'autowonder.service', 'release/auto-wonder.jar'} <= set(checksums):
        raise ValueError('Incomplete backup snapshot')
    for name, sha in checksums.items():
        path = Path(name)
        if path.is_absolute() or '..' in path.parts or '\n' in name or '\\' in name:
            raise ValueError('Unsafe backup checksum path')
        if not re.fullmatch(r'[0-9a-f]{64}', sha) or hashlib.sha256((root / path).read_bytes()).hexdigest() != sha:
            raise ValueError('Backup content checksum mismatch')
    files = [path for path in root.rglob('*') if path.is_file()]
    if any(path.is_symlink() for path in root.rglob('*')):
        raise ValueError('Backup links are not allowed')
    if {path.relative_to(root).as_posix() for path in files} - {'metadata.json'} != set(checksums):
        raise ValueError('Incomplete backup checksum inventory')
    (root / 'plan-fingerprint').write_text(metadata['plan'] + '\n')
    (root / 'release-name').write_text(metadata['release'] + '\n')
    (root / 'CHECKSUMS').write_text(''.join(
        hashlib.sha256(path.read_bytes()).hexdigest() + '  ./' + path.relative_to(root).as_posix() + '\n'
        for path in sorted(root.rglob('*')) if path.is_file()))


try:
    normalize(Path(sys.argv[1]))
except Exception:
    sys.exit('Legacy backup validation failed; original archive preserved')
