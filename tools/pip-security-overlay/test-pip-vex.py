#!/usr/bin/env python3
"""Negative controls for the exact pip-vendor VEX boundary."""
from copy import deepcopy
import ast
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from vendor_source_inventory import inventory, matching_sbom

spec = importlib.util.spec_from_file_location("pip_vex", Path(__file__).with_name("filter-pip-vendor-findings.py"))
vex = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vex)


class VexBoundary(unittest.TestCase):
    def setUp(self):
        self.proof = {"verifiedWheelSha256": vex.WHEEL_SHA, "verifiedWheelPath": "/usr/share/python-wheels/" + vex.WHEEL_NAME,
                      "pip": "26.2.2+pasturestack.1",
                      "urllib3": "2.8.0", "msgpackImplementation": "pip._vendor.msgpack.fallback",
                      "setuptoolsPackageAbsent": True, "vendorFilesByteCompared": 299,
                      "vulnerableModulesAbsent": ["msgpack._cmsgpack", "setuptools.wheel"],
                      "vendorSourceInventory": {
                          "complete": True, "noForeignMatchingDistribution": True, "expectedBomSha256": vex.BOM_SHA,
                          "sources": [{"kind": "wheel", "path": "/usr/share/python-wheels/" + vex.WHEEL_NAME, "sha256": vex.WHEEL_SHA},
                                      {"kind": "sbom", "path": "/licenses/pip-security/pip-vendor.cdx.json", "sha256": vex.BOM_SHA}]},
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
        self.assertEqual(len(document["statements"]), len(vex.RULES))
        self.assertEqual(self.raw, original, "raw evidence must not be mutated")

    def test_invalid_proof_fails_closed(self):
        for key, value in [("verifiedWheelSha256", "0" * 64), ("urllib3", "2.7.0"),
                           ("vendorFilesByteCompared", 300), ("setuptoolsPackageAbsent", False),
                           ("verifiedWheelPath", "/tmp/unverified/" + vex.WHEEL_NAME),
                           ("pip", "26.2.2"), ("vulnerableModulesAbsent", [])]:
            proof = dict(self.proof, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                vex.apply(self.raw, proof, "urn:test:image")

    def test_full_distribution_is_not_excluded(self):
        for package in self.raw["Results"][0]["Packages"]:
            package["AnalyzedBy"] = "python-pkg"
        result, document = vex.apply(self.raw, self.proof, "urn:test:image")
        self.assertEqual(len(result["Results"][0]["Vulnerabilities"]), len(vex.RULES))
        self.assertEqual(document["statements"], [])

    def test_missing_packages_is_not_excluded(self):
        self.raw["Results"][0]["Packages"] = []
        result, _ = vex.apply(self.raw, self.proof, "urn:test:image")
        self.assertEqual(len(result["Results"][0]["Vulnerabilities"]), len(vex.RULES))

    def test_wrong_version_is_not_excluded(self):
        for finding in self.raw["Results"][0]["Vulnerabilities"]:
            finding["InstalledVersion"] = "999.0"
        result, document = vex.apply(self.raw, self.proof, "urn:test:image")
        self.assertEqual(len(result["Results"][0]["Vulnerabilities"]), len(vex.RULES))
        self.assertEqual(document["statements"], [])

    def test_unknown_advisory_and_urllib3_are_retained(self):
        unknown = {"VulnerabilityID": "CVE-TEST-UNKNOWN", "PkgName": "setuptools", "InstalledVersion": "70.3.0"}
        urllib = {"VulnerabilityID": "CVE-2026-97689", "PkgName": "urllib3", "InstalledVersion": "2.7.0"}
        self.raw["Results"][0]["Vulnerabilities"] += [unknown, urllib]
        result, _ = vex.apply(self.raw, self.proof, "urn:test:image")
        self.assertEqual(result["Results"][0]["Vulnerabilities"], [unknown, urllib])

    def test_explicit_distribution_path_cannot_inherit_vendor_proof(self):
        for item in self.raw["Results"][0]["Vulnerabilities"]:
            item["PkgPath"] = "/unrelated/site-packages/" + item["PkgName"]
        result, document = vex.apply(self.raw, self.proof, "urn:test:image")
        self.assertEqual(len(result["Results"][0]["Vulnerabilities"]), len(vex.RULES))
        self.assertEqual(document["statements"], [])

    def test_missing_or_unrecognized_source_inventory_fails_closed(self):
        for change in [None, {"complete": False}, {"sources": []}]:
            proof = deepcopy(self.proof)
            proof["vendorSourceInventory"] = {} if change is None else dict(proof["vendorSourceInventory"], **change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                vex.apply(self.raw, proof, "urn:test:image")
        proof = deepcopy(self.proof)
        proof["vendorSourceInventory"]["sources"][0]["path"] = "/unrelated/" + vex.WHEEL_NAME
        with self.assertRaises(ValueError):
            vex.apply(self.raw, proof, "urn:test:image")

    def test_vulnerable_module_presence_fails_actual_proof_check(self):
        source = Path(__file__).with_name("check-pip-vendor-slice.py").read_text()
        tree = ast.parse(source)
        function = next(node for node in tree.body if isinstance(node, ast.FunctionDef)
                        and node.name == "require_absent_vulnerable_modules")
        namespace = {}
        exec(compile(ast.Module(body=[function], type_ignores=[]), "<actual-vendor-proof>", "exec"), namespace)
        check = namespace["require_absent_vulnerable_modules"]
        check(["pip/_vendor/msgpack/fallback.py", "pip/_vendor/pkg_resources/__init__.py"])
        for path in ["pip/_vendor/msgpack/_cmsgpack.so", "pip/_vendor/msgpack/_cmsgpack.pyd",
                     "pip/_vendor/setuptools/wheel.py"]:
            with self.subTest(path=path), self.assertRaises(AssertionError):
                check(name for name in [path])

    def test_actual_source_inventory_rejects_foreign_sbom_distribution_and_wheel(self):
        bom = json.dumps({"bomFormat": "CycloneDX", "components": [
            {"name": "msgpack", "version": "1.1.2"}, {"name": "setuptools", "version": "70.3.0"}]}).encode()
        with tempfile.TemporaryDirectory(prefix="pip-source-inventory-") as directory:
            root = Path(directory)
            known = root / "licenses/pip-security/pip-vendor.cdx.json"
            known.parent.mkdir(parents=True); known.write_bytes(bom)
            wheel = root / ("usr/share/python-wheels/" + vex.WHEEL_NAME)
            wheel.parent.mkdir(parents=True)
            with zipfile.ZipFile(wheel, "w") as archive:
                archive.writestr("pip/_vendor/bom.cdx.json", bom)
            wheel_sha = hashlib.sha256(wheel.read_bytes()).hexdigest()
            bom_sha = hashlib.sha256(bom).hexdigest()
            self.assertEqual(len(inventory(root, wheel_sha, bom_sha)["sources"]), 2)
            bad_inputs = {
                "foreign/bom.cdx.json": bom,
                "foreign/sbom.spdx.json": json.dumps({"spdxVersion": "SPDX-2.3", "packages": [
                    {"name": "setuptools", "versionInfo": "70.3.0"}]}).encode(),
                "outside-sys-path/msgpack.dist-info/METADATA": b"Name: msgpack\nVersion: 1.1.2\n",
                "foreign/copy.whl": wheel.read_bytes(),
            }
            for name, content in bad_inputs.items():
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(content)
                with self.subTest(path=name), self.assertRaises(ValueError):
                    inventory(root, wheel_sha, bom_sha)
                path.unlink()
            known.write_bytes(bom + b" ")
            with self.assertRaises(ValueError):
                inventory(root, wheel_sha, bom_sha)

    def test_unrelated_or_versionless_sbom_components_do_not_match(self):
        unrelated = {"spdxVersion": "SPDX-2.3", "packages": [
            {"name": "other", "externalRefs": [{"referenceLocator": "pkg:pypi/other@1.0"}]}]}
        self.assertFalse(matching_sbom(json.dumps(unrelated).encode()))
        versionless = {"bomFormat": "CycloneDX", "components": [{"name": "other"}, {}, {"name": "msgpack"}]}
        self.assertFalse(matching_sbom(json.dumps(versionless).encode()))
        primary = {"bomFormat": "CycloneDX", "metadata": {"component": {"name": "msgpack", "version": "1.1.2"}}}
        self.assertTrue(matching_sbom(json.dumps(primary).encode()))

    def test_source_walk_error_cannot_produce_complete_inventory(self):
        def unreadable(*args, **kwargs):
            kwargs["onerror"](PermissionError("unreadable source path"))
        with patch("vendor_source_inventory.os.walk", unreadable), self.assertRaises(PermissionError):
            inventory(Path("/"), vex.WHEEL_SHA, vex.BOM_SHA)


if __name__ == "__main__":
    unittest.main()
