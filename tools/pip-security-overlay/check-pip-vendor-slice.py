#!/usr/bin/env python3
"""Prove the exact installed pip vendor slice before producing narrow VEX data."""
import argparse
import hashlib
import importlib.metadata
import importlib.resources
import json
from pathlib import Path
import zipfile
import pip
from pip._vendor import msgpack, urllib3
from vendor_source_inventory import inventory

EXPECTED_SHA = "be5f63d537bbe611dc422fabb6740a44b5997888b592888df5091727f023802a"
BOM_SHA = "3581cb8f0a6128c19ccec4e9aeab0d3ac8212fd19bd19d485b2e24fd4dc10f2a"
FINDINGS = {
    "GHSA-6v7p-g79w-8964": ("msgpack", "1.1.2", "C/Cython unpacker is absent; runtime selects pure Python fallback"),
    "CVE-2026-57585": ("msgpack", "1.1.2", "C/Cython unpacker is absent; runtime selects pure Python fallback"),
    "CVE-2026-114613": ("msgpack", "1.1.2", "_unpacker.pyx/unpack.h strict-map-key C extension is absent; fallback.py is not affected"),
    "GHSA-j586-36cw-2gc2": ("msgpack", "1.1.2", "_unpacker.pyx/unpack.h strict-map-key C extension is absent; fallback.py is not affected"),
    "CVE-2025-47273": ("setuptools", "70.3.0", "setuptools/package_index.py is absent; only pkg_resources is vendored"),
    "CVE-2026-59890": ("setuptools", "70.3.0", "setuptools/command/egg_info.py and FileList are absent"),
    "GHSA-xmj3-9gf7-h52w": ("setuptools", "70.3.0", "setuptools/wheel.py Wheel._fix_namespace_packages is absent; only pkg_resources is vendored"),
}


def require_absent_vulnerable_modules(names):
    names = tuple(names)
    assert not any(name.startswith("pip/_vendor/msgpack/_cmsgpack") for name in names), "Native msgpack module present"
    assert not any(name.startswith("pip/_vendor/setuptools/") for name in names), "Full setuptools module present"


def verify(wheel):
    assert hashlib.sha256(wheel.read_bytes()).hexdigest() == EXPECTED_SHA
    assert pip.__version__ == "26.2.2+pasturestack.1"
    assert urllib3.__version__ == "2.8.0"
    assert msgpack.Unpacker.__module__ == "pip._vendor.msgpack.fallback"
    live = importlib.resources.files("pip")
    checked = 0
    with zipfile.ZipFile(wheel) as archive:
        names = archive.namelist()
        expected_files = set()
        for name in names:
            if not name.startswith("pip/_vendor/") or name.endswith("/"):
                continue
            assert live.joinpath(name.removeprefix("pip/")).read_bytes() == archive.read(name), name
            expected_files.add(name.removeprefix("pip/_vendor/"))
            checked += 1
        assert not any(name.startswith("pip/_vendor/") and name.endswith((".so", ".pyd")) for name in names)
        require_absent_vulnerable_modules(names)
    def live_files(directory, prefix=""):
        for entry in directory.iterdir():
            if entry.name == "__pycache__":
                continue
            relative = prefix + entry.name
            if entry.is_dir():
                yield from live_files(entry, relative + "/")
            else:
                yield relative
    observed_files = set(live_files(live.joinpath("_vendor")))
    require_absent_vulnerable_modules("pip/_vendor/" + name for name in observed_files)
    assert observed_files == expected_files, "Unexpected live vendor files"
    # A separate full setuptools installation cannot inherit the vendor-slice VEX.
    for distribution in importlib.metadata.distributions():
        if distribution.metadata.get("Name", "").lower() == "setuptools":
            assert distribution.version != "70.3.0", "A full setuptools 70.3.0 distribution is installed"
        if distribution.metadata.get("Name", "").lower() == "msgpack":
            assert distribution.version != "1.1.2", "A separate msgpack 1.1.2 distribution is installed"
    return {"verifiedWheelSha256": EXPECTED_SHA, "verifiedWheelPath": str(wheel.resolve()), "pip": pip.__version__,
            "urllib3": urllib3.__version__, "vendorFilesByteCompared": checked,
            "msgpackImplementation": msgpack.Unpacker.__module__,
            "setuptoolsPackageAbsent": True, "vulnerableModulesAbsent": ["msgpack._cmsgpack", "setuptools.wheel"],
            "scope": "this exact pip vendor slice only",
            "notAffected": [{"id": key, "package": value[0], "version": value[1],
                             "justification": "vulnerable_code_not_present", "detail": value[2]}
                            for key, value in FINDINGS.items()]}


if __name__ == "__main__":
    import sys
    if sys.flags.optimize:
        raise RuntimeError("Security verification must not run with Python optimization")
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    # The security proof is about the interpreter's actual selected seed, not
    # an arbitrary file supplied through a CLI path.
    try:
        import ensurepip
    except ImportError:
        # compose's distro runtime intentionally has no ensurepip package.
        # Its build retains the verified input at this fixed, non-user path.
        result = verify(Path("/opt/python-security/pip-26.2.2+pasturestack.1-py3-none-any.whl"))
    else:
        with ensurepip._get_pip_whl_path_ctx() as wheel:
            result = verify(Path(wheel))
    result["vendorSourceInventory"] = inventory(Path("/"), EXPECTED_SHA, BOM_SHA)
    data = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(data)
    print(data, end="")
