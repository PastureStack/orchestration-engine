#!/usr/bin/env python3
"""Install the explicitly identified downstream wheel into a CPython Lib tree.

Run before compiling CPython (Lib/) or against its private installed stdlib.
This never edits a system interpreter implicitly: --stdlib is mandatory.
"""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import sys
import zipfile

if sys.flags.optimize:
    raise RuntimeError("Security install verification must not run with Python optimization")

parser = argparse.ArgumentParser()
parser.add_argument("--stdlib", type=Path, required=True)
parser.add_argument("--wheel", type=Path, required=True)
parser.add_argument("--sha256", required=True)
args = parser.parse_args()
version = "26.2.2+pasturestack.1"
wheel = args.wheel.resolve()
assert wheel.name == f"pip-{version}-py3-none-any.whl"
assert hashlib.sha256(wheel.read_bytes()).hexdigest() == args.sha256
with zipfile.ZipFile(wheel) as archive:
    metadata = archive.read(f"pip-{version}.dist-info/METADATA").decode()
    assert f"\nVersion: {version}\n" in metadata
    assert re.search(rb"__version__\s*=\s*(?:version\s*=\s*)?['\"]2\.8\.0['\"]",
                     archive.read("pip/_vendor/urllib3/_version.py"))
ensurepip = args.stdlib.resolve() / "ensurepip"
init = ensurepip / "__init__.py"
text = init.read_text()
match = re.search(r'^_PIP_VERSION = [\"\']([^\"\']+)[\"\']$', text, re.MULTILINE)
assert match, "ensurepip version declaration not found"
old = ensurepip / "_bundled" / f"pip-{match.group(1)}-py3-none-any.whl"
assert old.is_file(), "Expected bundled wheel missing; do not modify another layout"
target = old.parent / wheel.name
shutil.copyfile(wheel, target)
init.write_text(text[:match.start()] + f'_PIP_VERSION = "{version}"' + text[match.end():])
if old != target:
    old.unlink()
assert hashlib.sha256(target.read_bytes()).hexdigest() == args.sha256
print(f"ENSUREPIP_OVERLAY_OK version={version} sha256={args.sha256}")
