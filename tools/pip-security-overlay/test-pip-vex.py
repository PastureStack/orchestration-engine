#!/usr/bin/env python3
"""Negative controls for the exact pip-vendor VEX boundary."""
from copy import deepcopy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("pip_vex", Path(__file__).with_name("filter-pip-vendor-findings.py"))
vex = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vex)


class VexBoundary(unittest.TestCase):
    def setUp(self):
        self.proof = {"verifiedWheelSha256": vex.WHEEL_SHA, "pip": "26.2.2+pasturestack.1",
                      "urllib3": "2.8.0", "msgpackImplementation": "pip._vendor.msgpack.fallback",
                      "setuptoolsPackageAbsent": True, "vendorFilesByteCompared": 299,
                      "notAffected": [{"id": key, "package": value[0], "version": value[1],
                                       "detail": "Test fixture only"} for key, value in vex.RULES.items()]}
        self.raw = {"Results": [{"Type": "python-pkg", "Packages": [
            {"Name": name, "Version": version, "AnalyzedBy": "sbom"}
            for name, version in set(vex.RULES.values())], "Vulnerabilities": [
            {"VulnerabilityID": key, "PkgName": value[0], "InstalledVersion": value[1]}
            for key, value in vex.RULES.items()]}]}

    def test_exact_slice_only(self):
        original = deepcopy(self.raw)
        result, document = vex.apply(self.raw, self.proof, "urn:test:exact-image")
        self.assertEqual(result["Results"][0]["Vulnerabilities"], [])
        self.assertEqual(len(document["statements"]), 4)
        self.assertEqual(self.raw, original, "raw evidence must not be mutated")

    def test_invalid_proof_fails_closed(self):
        for key, value in [("verifiedWheelSha256", "0" * 64), ("urllib3", "2.7.0"),
                           ("vendorFilesByteCompared", 300), ("setuptoolsPackageAbsent", False)]:
            proof = dict(self.proof, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                vex.apply(self.raw, proof, "urn:test:image")

    def test_full_distribution_is_not_excluded(self):
        for package in self.raw["Results"][0]["Packages"]:
            package["AnalyzedBy"] = "python-pkg"
        result, document = vex.apply(self.raw, self.proof, "urn:test:image")
        self.assertEqual(len(result["Results"][0]["Vulnerabilities"]), 4)
        self.assertEqual(document["statements"], [])

    def test_missing_packages_is_not_excluded(self):
        self.raw["Results"][0]["Packages"] = []
        result, _ = vex.apply(self.raw, self.proof, "urn:test:image")
        self.assertEqual(len(result["Results"][0]["Vulnerabilities"]), 4)

    def test_wrong_version_is_not_excluded(self):
        for finding in self.raw["Results"][0]["Vulnerabilities"]:
            finding["InstalledVersion"] = "999.0"
        result, document = vex.apply(self.raw, self.proof, "urn:test:image")
        self.assertEqual(len(result["Results"][0]["Vulnerabilities"]), 4)
        self.assertEqual(document["statements"], [])

    def test_unknown_advisory_and_urllib3_are_retained(self):
        unknown = {"VulnerabilityID": "CVE-TEST-UNKNOWN", "PkgName": "setuptools", "InstalledVersion": "70.3.0"}
        urllib = {"VulnerabilityID": "CVE-2026-97689", "PkgName": "urllib3", "InstalledVersion": "2.7.0"}
        self.raw["Results"][0]["Vulnerabilities"] += [unknown, urllib]
        result, _ = vex.apply(self.raw, self.proof, "urn:test:image")
        self.assertEqual(result["Results"][0]["Vulnerabilities"], [unknown, urllib])


if __name__ == "__main__":
    unittest.main()
