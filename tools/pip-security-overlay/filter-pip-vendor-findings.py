#!/usr/bin/env python3
"""Apply narrowly proven pip-vendor VEX, retaining the original Trivy report."""
import argparse
from copy import deepcopy
from datetime import datetime, timezone
import json
from pathlib import Path

WHEEL_SHA = "be5f63d537bbe611dc422fabb6740a44b5997888b592888df5091727f023802a"
RULES = {
    "GHSA-6v7p-g79w-8964": ("msgpack", "1.1.2"),
    "CVE-2026-57585": ("msgpack", "1.1.2"),
    "CVE-2025-47273": ("setuptools", "70.3.0"),
    "CVE-2026-59890": ("setuptools", "70.3.0"),
}


def apply(raw, proof, subject):
    valid = (proof["verifiedWheelSha256"] == WHEEL_SHA and
             proof["pip"] == "26.2.2+pasturestack.1" and proof["urllib3"] == "2.8.0" and
             proof["msgpackImplementation"] == "pip._vendor.msgpack.fallback" and
             proof["setuptoolsPackageAbsent"] is True and proof["vendorFilesByteCompared"] == 299)
    if not valid:
        raise ValueError("Exact installed pip vendor proof is required")
    reasons = {item["id"]: item for item in proof["notAffected"]}
    result = deepcopy(raw)
    statements = []
    for section in result.get("Results", []):
        retained = []
        for item in section.get("Vulnerabilities", []):
            key = item.get("VulnerabilityID")
            expected = RULES.get(key)
            actual = (item.get("PkgName"), item.get("InstalledVersion"))
            packages = [pkg for pkg in section.get("Packages", []) if
                        (pkg.get("Name"), pkg.get("Version")) == actual]
            reason = reasons.get(key, {})
            eligible = (expected == actual and key in reasons and
                        (reason.get("package"), reason.get("version")) == actual and
                        section.get("Type") == "python-pkg" and packages and
                        all(pkg.get("AnalyzedBy") == "sbom" for pkg in packages))
            if not eligible:
                retained.append(item)
                continue
            purl = "pkg:pypi/" + actual[0] + "@" + actual[1]
            statements.append({
                "vulnerability": {"name": key},
                "products": [{"@id": subject, "subcomponents": [{"@id": purl}]}],
                "status": "not_affected", "justification": "vulnerable_code_not_present",
                "impact_statement": reasons[key]["detail"] + "; exact pip wheel SHA-256 " + WHEEL_SHA,
            })
        section["Vulnerabilities"] = retained
    vex = {"@context": "https://openvex.dev/ns/v0.2.0", "@id": subject + "/pip-vendor-vex",
           "author": "PastureStack", "timestamp": datetime.now(timezone.utc).isoformat(),
           "version": 1, "statements": statements}
    return result, vex


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", type=Path, required=True)
    parser.add_argument("--proof", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--vex", type=Path, required=True)
    parser.add_argument("--subject", required=True)
    args = parser.parse_args()
    report, vex = apply(json.loads(args.raw.read_text()), json.loads(args.proof.read_text()), args.subject)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    args.vex.write_text(json.dumps(vex, indent=2) + "\n")
    print(f"PROVEN_PIP_VENDOR_VEX statements={len(vex['statements'])}")
