"""Write-through boundary shared by existing Python manifest writers."""
import json
import os
from pathlib import Path
import subprocess
import sys

from operation_metrics import measure

def _run(path, value=None, command="checkpoint"):
    if os.environ.get("AUTOWONDER_OPERATIONS_INTERNAL") == "1":
        return
    path = Path(path)
    if value is None:
        if not path.is_file():
            return
        try:
            value = json.loads(path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError:
            if path.suffix.lower() == ".json":
                raise
            return
    if not isinstance(value, dict) or not value.get("operationsStore"):
        return
    with measure('checkpoint_hook'):
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("operations-store.py")),
                                 command, "--manifest", str(path)],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if result.returncode:
            raise RuntimeError("Operations checkpoint failed; stop before further changes")

        if command == "checkpoint" and isinstance(value, dict):
            value["operationsStore"] = json.loads(path.read_text(encoding="utf-8"))["operationsStore"]

def checkpoint(path, value=None):
    _run(path, value)

def assert_current(path):
    _run(path, command="assert-current")
