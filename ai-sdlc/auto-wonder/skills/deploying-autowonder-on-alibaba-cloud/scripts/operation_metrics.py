"""Optional local phase timings. Never affect cloud operation results or retries."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import stat
import sys
import time

_STAGES = frozenset({'bundle_collect', 'checkpoint', 'checkpoint_hook', 'oss_transport', 'restore'})
_COUNTS = frozenset({'files', 'bytes', 'calls'})
_PHASES = frozenset({'bootstrap', 'resolve', 'refresh', 'verify-targets', 'inventory',
                     'plan', 'approve', 'build', 'backup', 'runtime-config', 'stage',
                     'database-migrate', 'rolling-upgrade', 'acceptance'})


def _append(path, record):
    # Windows mode bits cannot prove ACL privacy. Until native ACL verification is
    # available, fail closed for telemetry while leaving the cloud operation alone.
    if os.name == 'nt':
        raise OSError('Private metrics storage is not verified on this platform')
    parts = Path(os.path.abspath(path)).parts
    directory = os.open(parts[0], os.O_RDONLY | os.O_DIRECTORY)
    descriptor = None
    try:
        # Open each directory relative to an already-open descriptor so neither
        # parent symlinks nor a path replacement can redirect the append.
        for component in parts[1:-1]:
            child = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory)
            directory = child
        descriptor = os.open(parts[-1], os.O_WRONLY | os.O_APPEND | os.O_CREAT |
                             os.O_NOFOLLOW | os.O_NONBLOCK, 0o600, dir_fd=directory)
        info = os.fstat(descriptor)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or
                info.st_mode & 0o077 or info.st_nlink != 1):
            raise OSError('Unsafe metrics file')
        payload = (json.dumps(record, separators=(',', ':'), allow_nan=False) + '\n').encode('ascii')
        if os.write(descriptor, payload) != len(payload):
            raise OSError('Incomplete metrics record')
    finally:
        if descriptor is not None:
            os.close(descriptor)
        os.close(directory)


def _warning():
    try:
        print('WARNING: optional operation metrics could not be recorded.', file=sys.stderr)
    except Exception:
        pass


@contextmanager
def measure(stage):
    """Time a fixed phase, optionally accepting bounded files/bytes/calls counts.

    Absence of AUTOWONDER_METRICS_FILE disables even clock reads. The workflow
    chooses a private existing directory; this helper never creates directories.
    Exceptions in the operation always propagate unchanged, including after a
    metrics write failure. No command, path, output or exception text is logged.
    """
    path = os.environ.get('AUTOWONDER_METRICS_FILE')
    counts = {}
    if not path:
        yield counts
        return
    if stage not in _STAGES:
        _warning()
        yield counts
        return
    try:
        started = time.monotonic()
    except Exception:
        _warning()
        yield counts
        return
    status = 'failure'
    try:
        yield counts
        status = 'success'
    finally:
        try:
            record = {'stage': stage, 'elapsed_seconds': round(max(0, time.monotonic() - started), 6),
                      'status': status, 'counts': {key: value for key, value in counts.items()
                          if key in _COUNTS and type(value) is int and 0 <= value <= 2**63 - 1}}
            phase = os.environ.get('AUTOWONDER_METRICS_PHASE')
            if phase in _PHASES:
                record['phase'] = phase
            _append(path, record)
        except Exception:
            _warning()
