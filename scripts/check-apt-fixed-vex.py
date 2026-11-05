#!/usr/bin/env python3
"""Validate only the official USN-8996-1 APT pair and Trivy's native VEX result.

This verifier never filters a report or changes the release severity policy.
"""
import argparse
from collections import Counter
import copy
import hashlib
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parent.parent
CVE = "CVE-2026-109518"
VERSION = "3.2.1ubuntu0.1"
USN = "https://ubuntu.com/security/notices/USN-8996-1"
ARCHIVES = {
    "apt": "0de729b8fe0e1c67c04e8e8d5bd6e0420dbd47f75b93f0b71c8e740edc2d5ffc",
    "libapt-pkg7.0": "94e5d41303265b66af0f8c70ade18bfb77260ad286edee6c8045faddef82b94c",
}
PURLS = {name: "pkg:deb/ubuntu/" + name + "@" + VERSION + "?arch=amd64&distro=ubuntu-26.04"
         for name in ARCHIVES}
ELF_MANIFEST_SHA256 = "365b11be723d25f344a5f5bed1a0bb457a45b660e10ad1968edb60d6f043c5f5"


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def document_guard(document):
    require(set(document) == {"@context", "@id", "author", "timestamp", "version", "statements"}, "closed OpenVEX document")
    require(document["@context"] == "https://openvex.dev/ns/v0.2.0"
            and document["@id"] == "https://github.com/PastureStack/orchestration-engine/security/openvex/USN-8996-1"
            and document["author"] == "PastureStack Security" and type(document["version"]) is int
            and document["version"] == 1, "exact advisory identity")
    require(re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", document["timestamp"]) is not None, "UTC timestamp")
    require(len(document["statements"]) == 1, "one exact advisory statement")
    statement = document["statements"][0]
    require(set(statement) == {"vulnerability", "products", "status", "impact_statement"}, "closed fixed statement")
    require(statement["vulnerability"] == {"name": CVE} and statement["status"] == "fixed", "official fixed CVE only")
    products = statement["products"]
    require(len(products) == 2 and all(set(p) == {"@id"} for p in products)
            and {p["@id"] for p in products} == set(PURLS.values()), "exact two version/arch/distro subjects")
    impact = statement["impact_statement"]
    require(all(value in impact for value in [USN, VERSION, *ARCHIVES.values()]), "official notice and archive hashes")


def checksum_rows(text):
    result = {}
    for line in text.splitlines():
        fields = line.split()
        require(len(fields) == 2 and re.fullmatch(r"[0-9a-f]{64}", fields[0]) is not None, "SHA256 row")
        require(fields[1] not in result, "duplicate ELF path")
        result[fields[1]] = fields[0]
    return result


def input_guard(document, manifest, dpkg, inspect, raw, installed):
    document_guard(document)
    require(hashlib.sha256(manifest.encode()).hexdigest() == ELF_MANIFEST_SHA256, "official archive ELF manifest")
    expected = checksum_rows(manifest)
    require(len(expected) == 19 and checksum_rows(installed) == expected, "all nineteen installed official ELF bytes")
    require(len(inspect) == 1 and inspect[0]["Architecture"] == "amd64" and inspect[0]["Os"] == "linux", "one Linux amd64 image")
    image = inspect[0]["Id"]
    require(re.fullmatch(r"sha256:[0-9a-f]{64}", image) is not None, "immutable image identity")
    metadata = raw["Metadata"]
    require(metadata["ImageID"] == image and metadata["OS"]["Family"] == "ubuntu"
            and metadata["OS"]["Name"] == "26.04", "same Ubuntu image as native readback")
    lines = dpkg.splitlines()
    require(lines and lines[0] == "package\tversion", "native dpkg inventory")
    os_results = [r for r in raw["Results"] if r.get("Class") == "os-pkgs" and r.get("Type") == "ubuntu"]
    require(len(os_results) == 1, "one Ubuntu package inventory")
    for name, purl in PURLS.items():
        native = [line.split("\t") for line in lines[1:] if line.split("\t", 1)[0].split(":", 1)[0] == name]
        require(len(native) == 1 and len(native[0]) == 2 and native[0][1] == VERSION, "exact installed pair " + name)
        packages = [p for p in os_results[0]["Packages"] if p.get("Name") == name]
        require(len(packages) == 1, "one raw package " + name)
        package = packages[0]
        require(package.get("ID") == name + "@" + VERSION and package.get("Version") == VERSION
                and not package.get("Release") and not package.get("Epoch") and package.get("Arch") == "amd64"
                and package.get("SrcName") == "apt" and package.get("SrcVersion") == VERSION
                and not package.get("SrcRelease") and not package.get("SrcEpoch")
                and package.get("Identifier", {}).get("PURL") == purl, "exact raw binary/source/PURL " + name)
    return image


def covered(finding):
    name = finding.get("PkgName")
    return (finding.get("VulnerabilityID") == CVE and name in PURLS
            and finding.get("PkgID") == name + "@" + VERSION and finding.get("InstalledVersion") == VERSION
            and finding.get("PkgIdentifier", {}).get("PURL") == PURLS[name])


def package_identity(result):
    """Keep every package field/count; canonicalize only documented graph metadata.

    Trivy v0.74.0 vex/vex.go:78 forces SBOM regeneration. Its sbom/io/encode.go:238
    fills empty package IDs with Name@Version; dependency/id.go:37 defines UID as
    a package-graph hash. Imported Python SBOM UIDs can change between scans.
    Neither report is changed: original IDs/UIDs remain recorded in the proof.
    """
    rows, mapping, uid_owners = [], [], {}
    language = result.get("Class") == "lang-pkgs" and result.get("Type") in {"jar", "python-pkg"}
    for original in result.get("Packages", []):
        package = copy.deepcopy(original)
        change = {}
        if language and not package.get("ID"):
            require(isinstance(package.get("Name"), str) and isinstance(package.get("Version"), str), "graph ID source fields")
            require("ID" not in package or package["ID"] == "", "empty graph ID only")
            package["ID"] = package["Name"] + "@" + package["Version"]
            change.update(originalId=original.get("ID"), canonicalId=package["ID"])
        identifier = package.get("Identifier", {})
        purl = identifier.get("PURL", "")
        if (result.get("Class") == "lang-pkgs" and result.get("Type") == "python-pkg"
                and package.get("AnalyzedBy") == "sbom" and not package.get("FilePath") and purl.startswith("pkg:pypi/")
                and identifier.get("BOMRef") == purl):
            uid = identifier.get("UID")
            require(isinstance(uid, str) and re.fullmatch(r"[0-9a-f]{1,16}", uid) is not None, "derived Python SBOM graph UID")
            del identifier["UID"]
            stable = json.dumps(package, sort_keys=True, separators=(",", ":"))
            canonical = "package-content-sha256:" + hashlib.sha256(stable.encode()).hexdigest()
            require(uid not in uid_owners or uid_owners[uid] == canonical, "graph UID collision across distinct packages")
            uid_owners[uid] = canonical
            identifier["UID"] = canonical
            change.update(originalUid=uid, canonicalUid=canonical)
        rows.append(json.dumps(package, sort_keys=True, separators=(",", ":")))
        if change:
            mapping.append(dict(purl=purl, **change))
    return Counter(rows), sorted(mapping, key=lambda row: json.dumps(row, sort_keys=True))


def package_identity_proof(raw, native):
    result = []
    for old, new in zip(raw["Results"], native["Results"]):
        before, old_mapping = package_identity(old)
        after, new_mapping = package_identity(new)
        require(before == after, "complete package content/multiplicity drift")
        result.append(dict(target=old.get("Target"), type=old.get("Type"), count=sum(before.values()),
                           completePackageMultisetSha256=hashlib.sha256(json.dumps(sorted(before.items())).encode()).hexdigest(),
                           rawDerivedMetadata=old_mapping, nativeDerivedMetadata=new_mapping))
    return result


def output_guard(raw, native):
    for key in ("ArtifactName", "ArtifactType", "Metadata"):
        require(native.get(key) == raw.get(key), "same native scan identity/inventory " + key)
    before, after = raw["Results"], native["Results"]
    require(len(before) == len(after), "same scan target closure")
    removed = 0
    for old, new in zip(before, after):
        for key in ("Target", "Class", "Type"):
            require(old.get(key) == new.get(key), "native scan target/package drift " + key)
        expected = [v for v in old.get("Vulnerabilities", []) if not covered(v)]
        removed += len(old.get("Vulnerabilities", [])) - len(expected)
        require(new.get("Vulnerabilities", []) == expected, "native VEX must preserve every unrelated finding")
    require(0 <= removed <= 2, "only the two exact fixed subjects may be qualified")
    package_identity_proof(raw, native)
    return removed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("dpkg", "inspect", "raw", "installed"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--vex", type=Path, default=ROOT / "security/ubuntu-apt-fixed.openvex.json")
    parser.add_argument("--manifest", type=Path, default=ROOT / "security/ubuntu-apt-fixed.sha256")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--inputs-only", action="store_true")
    mode.add_argument("--native", type=Path)
    parser.add_argument("--proof", type=Path)
    args = parser.parse_args()
    document = json.loads(args.vex.read_bytes())
    raw = json.loads(args.raw.read_bytes())
    image = input_guard(document, args.manifest.read_text(), args.dpkg.read_text(),
                        json.loads(args.inspect.read_bytes()), raw, args.installed.read_text())
    if args.inputs_only:
        require(args.proof is None, "input validation is not final qualification")
        print("APT_FIXED_VEX_INPUTS_OK pair=2 official_elf_bytes=19")
        return
    require(args.proof is not None, "native output proof required")
    removed = output_guard(raw, json.loads(args.native.read_bytes()))
    inputs = {name: getattr(args, name) for name in ("dpkg", "inspect", "raw", "installed", "vex", "manifest", "native")}
    proof = dict(status="PASS_EXACT_NATIVE_TRIVY_FIXED_APT_VEX", imageId=image, cve=CVE,
                 version=VERSION, purls=PURLS, officialArchiveSha256=ARCHIVES, installedOfficialElfCount=19,
                 qualifiedFixedOccurrences=removed, everyUnrelatedFindingPreserved=True,
                 scannerDerivedPackageIdentity=package_identity_proof(raw, json.loads(args.native.read_bytes())),
                 inputSha256={name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in inputs.items()},
                 rawReportRewritten=False, severityPolicyChanged=False, pipOverlayOwnerChanged=False)
    args.proof.write_text(json.dumps(proof, sort_keys=True, indent=2) + "\n")
    print("APT_FIXED_NATIVE_VEX_OK qualified_fixed=%d unrelated_preserved=true" % removed)


if __name__ == "__main__":
    main()
