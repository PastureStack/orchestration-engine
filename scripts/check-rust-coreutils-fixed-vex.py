#!/usr/bin/env python3
"""Bind Canonical's exact backport to native bytes and Trivy's unchanged reports."""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
VERSION = "0.10.0-1ubuntu2~26.04.1"
ARCHIVE = "963dbc5efa92141e7056a9e9f3431ec7f352f2a7c762f2b3242443df39be9115"
ELF = "f84e76ea4da1e0c4e92254bd0d7dccdabe2c8b3098e734f27997dae3ee5d8e02"
PURL = "pkg:deb/ubuntu/rust-coreutils@" + VERSION + "?arch=amd64&distro=ubuntu-26.04"
FIXED = {"CVE-2026-" + suffix for suffix in (
    "35341", "35344", "35345", "35348", "35350", "35351", "35357", "35359",
    "35360", "35363", "35364", "35367", "35368", "35373", "35374", "35377")}
NOTICE = "https://discourse.ubuntu.com/t/rust-coreutils-0-10-now-available-for-ubuntu-26-04-lts/88120"
EXTRA = "https://ubuntu.com/security/cves/CVE-2026-35341.json"
IDENTITY = "https://github.com/PastureStack/orchestration-engine/security/openvex/rust-coreutils-0.10.0-resolute"
spec = importlib.util.spec_from_file_location("unchanged_apt_vex_comparison", ROOT / "scripts/check-apt-fixed-vex.py")
apt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apt)


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def document():
    original = json.loads((ROOT / "security/ubuntu-apt-fixed.openvex.json").read_bytes())
    apt.document_guard(original)
    result = copy.deepcopy(original)
    result.update({"@id": IDENTITY, "timestamp": "2026-11-10T08:00:00Z"})
    for cve in sorted(FIXED):
        result["statements"].append({"vulnerability": {"name": cve}, "products": [{"@id": PURL}],
            "status": "fixed", "impact_statement": (
                "Canonical documents this backport as fixed in " + VERSION + ". " + NOTICE + "; " + EXTRA
                + " explicitly confirms the updates-pocket fix for CVE-2026-35341 while the security-pocket status lags. "
                + "Official amd64 archive SHA256 " + ARCHIVE + "; installed /usr/bin/coreutils ELF SHA256 " + ELF
                + ". CI must verify the same Dapper image, installed package and bytes before using native Trivy OpenVEX. "
                + "No other version, architecture, distribution, package or CVE is covered.")})
    return result


def document_guard(value):
    require(value == document(), "exact fixed subjects and preserved APT statement")


def package_version(package):
    version = package.get("Version", "")
    release = package.get("Release")
    return version + ("-" + release if release else "")


def inputs(value, dpkg, inspect, raw, installed):
    document_guard(value)
    require(apt.checksum_rows(installed) == {"/usr/bin/coreutils": ELF}, "installed official backport ELF bytes")
    require(len(inspect) == 1 and inspect[0].get("Architecture") == "amd64" and inspect[0].get("Os") == "linux", "Linux amd64 image")
    image = inspect[0].get("Id")
    require(isinstance(image, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", image), "immutable image ID")
    require(raw.get("ArtifactType") == "container_image" and re.fullmatch(
        r"pasturestack/orchestration-engine-dapper:[0-9a-f]{40}", raw.get("ArtifactName", "")), "builder image scope only")
    metadata = raw.get("Metadata", {})
    require(metadata.get("ImageID") == image and metadata.get("OS", {}).get("Family") == "ubuntu"
            and metadata.get("OS", {}).get("Name") == "26.04", "same native Ubuntu image")
    lines = dpkg.splitlines()
    require(lines and lines[0] == "package\tversion", "native dpkg inventory")
    rows = [line.split("\t") for line in lines[1:] if line.split("\t", 1)[0].split(":", 1)[0] == "rust-coreutils"]
    require(len(rows) == 1 and len(rows[0]) == 2 and rows[0][1] == VERSION, "exact installed backport")
    os_rows = [r for r in raw.get("Results", []) if r.get("Class") == "os-pkgs" and r.get("Type") == "ubuntu"]
    require(len(os_rows) == 1, "one native Ubuntu package inventory")
    packages = [p for p in os_rows[0].get("Packages", []) if p.get("Name") == "rust-coreutils"]
    require(len(packages) == 1, "one raw package")
    package = packages[0]
    require(package.get("ID") == "rust-coreutils@" + VERSION and package_version(package) == VERSION
            and package.get("Arch") == "amd64" and package.get("SrcName") == "rust-coreutils"
            and package.get("Identifier", {}).get("PURL") == PURL, "raw package/version/arch/source/PURL")
    return image


def covered(finding):
    return (finding.get("VulnerabilityID") in FIXED and finding.get("PkgName") == "rust-coreutils"
            and finding.get("PkgID") == "rust-coreutils@" + VERSION and finding.get("InstalledVersion") == VERSION
            and finding.get("PkgIdentifier", {}).get("PURL") == PURL)


def output(raw, native):
    for key in ("ArtifactName", "ArtifactType", "Metadata"):
        require(raw.get(key) == native.get(key), "same native image/inventory " + key)
    before, after = raw["Results"], native["Results"]
    require(len(before) == len(after), "same complete target set")
    removed = 0
    for old, new in zip(before, after):
        for key in ("Target", "Class", "Type"):
            require(old.get(key) == new.get(key), "same target " + key)
        expected = [v for v in old.get("Vulnerabilities", []) if not covered(v)]
        removed += len(old.get("Vulnerabilities", [])) - len(expected)
        require(new.get("Vulnerabilities", []) == expected, "every unrelated finding must remain")
    require(0 <= removed <= len(FIXED), "only exact official fixed subjects")
    apt.package_identity_proof(raw, native)
    return removed


def main():
    parser = argparse.ArgumentParser()
    for name in ("dpkg", "inspect", "raw", "installed", "vex"):
        parser.add_argument("--" + name, type=Path, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--inputs-only", action="store_true")
    mode.add_argument("--native", type=Path)
    parser.add_argument("--proof", type=Path)
    args = parser.parse_args()
    vex = json.loads(args.vex.read_bytes()); raw = json.loads(args.raw.read_bytes())
    image = inputs(vex, args.dpkg.read_text(), json.loads(args.inspect.read_bytes()), raw, args.installed.read_text())
    if args.inputs_only:
        require(args.proof is None, "input validation is not final qualification")
        print("RUST_FIXED_NATIVE_INPUTS_OK subjects=16 official_elf=1")
        return
    require(args.proof is not None, "final proof required")
    native = json.loads(args.native.read_bytes()); removed = output(raw, native)
    paths = {n: getattr(args, n) for n in ("dpkg", "inspect", "raw", "installed", "vex", "native")}
    result = dict(status="PASS_EXACT_NATIVE_TRIVY_FIXED_RUST_BACKPORT", imageId=image, phase="builder",
        fixedSubjectCount=16, qualifiedFixedOccurrences=removed, packagePurl=PURL, officialArchiveSha256=ARCHIVE,
        installedOfficialElfSha256=ELF, officialSources=[NOTICE, EXTRA], everyUnrelatedFindingPreserved=True,
        rawReportRewritten=False, severityPolicyChanged=False,
        inputSha256={n: hashlib.sha256(p.read_bytes()).hexdigest() for n, p in paths.items()},
        scannerDerivedPackageIdentity=apt.package_identity_proof(raw, native))
    args.proof.write_text(json.dumps(result, sort_keys=True, indent=2) + "\n")
    print("RUST_FIXED_NATIVE_VEX_OK qualified_fixed=%d unrelated_preserved=true" % removed)


if __name__ == "__main__":
    main()
