#!/usr/bin/env python3
"""Negative controls for the exact builder-only official backport qualification."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("rust_fixed", Path(__file__).with_name("check-rust-coreutils-fixed-vex.py"))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


class RustFixedTests(unittest.TestCase):
    def setUp(self):
        self.inspect = [{"Architecture": "amd64", "Os": "linux", "Id": "sha256:" + "a" * 64}]
        package = {"Name": "rust-coreutils", "ID": "rust-coreutils@" + m.VERSION, "Version": "0.10.0",
            "Release": "1ubuntu2~26.04.1", "SrcName": "rust-coreutils", "Arch": "amd64", "Identifier": {"PURL": m.PURL}}
        self.raw = {"ArtifactName": "pasturestack/orchestration-engine-dapper:" + "b" * 40,
            "ArtifactType": "container_image", "Metadata": {"ImageID": self.inspect[0]["Id"], "OS": {"Family": "ubuntu", "Name": "26.04"}},
            "Results": [{"Target": "ubuntu", "Class": "os-pkgs", "Type": "ubuntu", "Packages": [package], "Vulnerabilities": []}]}
        self.row = {"PkgName": "rust-coreutils", "PkgID": "rust-coreutils@" + m.VERSION,
            "InstalledVersion": m.VERSION, "PkgIdentifier": {"PURL": m.PURL}, "Severity": "MEDIUM"}
        self.raw["Results"][0]["Vulnerabilities"] = [dict(self.row, VulnerabilityID=cve) for cve in sorted(m.FIXED)]
        self.raw["Results"][0]["Vulnerabilities"].append(dict(self.row, VulnerabilityID="CVE-2026-35352"))
        self.native = copy.deepcopy(self.raw)
        self.native["Results"][0]["Vulnerabilities"] = self.native["Results"][0]["Vulnerabilities"][-1:]
        self.dpkg = "package\tversion\nrust-coreutils\t" + m.VERSION + "\n"
        self.installed = m.ELF + "  /usr/bin/coreutils\n"

    def inputs(self, **changes):
        values = dict(value=m.document(), dpkg=self.dpkg, inspect=self.inspect, raw=self.raw, installed=self.installed)
        values.update(changes)
        return m.inputs(**values)

    def test_exact_backport_native_bytes_and_remaining_unfixed_finding(self):
        self.assertEqual(self.inspect[0]["Id"], self.inputs())
        self.assertEqual(16, m.output(self.raw, self.native))

    def test_modified_or_duplicate_elf_is_rejected(self):
        for text in ("0" * 64 + "  /usr/bin/coreutils\n", self.installed * 2, self.installed.replace("/usr/bin/coreutils", "/tmp/coreutils")):
            with self.assertRaises(ValueError): self.inputs(installed=text)

    def test_other_installed_version_cannot_borrow_the_fixed_statement(self):
        with self.assertRaises(ValueError): self.inputs(dpkg=self.dpkg.replace(m.VERSION, "0.8.0-0ubuntu3"))

    def test_image_architecture_os_and_identity_are_closed(self):
        for field, value in (("Architecture", "arm64"), ("Os", "windows"), ("Id", "sha256:" + "c" * 64)):
            inspect = copy.deepcopy(self.inspect); inspect[0][field] = value
            with self.assertRaises(ValueError): self.inputs(inspect=inspect)
        raw = copy.deepcopy(self.raw); raw["ArtifactName"] = "ghcr.io/pasturestack/server:1.6.594"
        with self.assertRaises(ValueError): self.inputs(raw=raw)

    def test_package_version_architecture_source_and_purl_are_closed(self):
        for field, value in (("Version", "0.8.0"), ("Arch", "arm64"), ("SrcName", "another-source"), ("Identifier", {"PURL": m.PURL.replace("ubuntu-26.04", "debian-13")})):
            raw = copy.deepcopy(self.raw); raw["Results"][0]["Packages"][0][field] = value
            with self.assertRaises(ValueError): self.inputs(raw=raw)

    def test_injected_subject_or_cve_cannot_enter_the_document(self):
        for field, value in (("products", [{"@id": m.PURL.replace("amd64", "arm64")}]), ("vulnerability", {"name": "CVE-2026-35352"})):
            document = m.document(); document["statements"][-1][field] = value
            with self.assertRaises(ValueError): self.inputs(value=document)

    def test_apt_statement_and_all_original_subjects_are_preserved(self):
        document = m.document(); document["statements"].pop(0)
        with self.assertRaises(ValueError): self.inputs(value=document)

    def test_unfixed_rust_findings_and_other_purls_are_never_covered(self):
        for cve in ("CVE-2026-35352", "CVE-2026-35354", "CVE-2026-35370", "CVE-2026-35371"):
            self.assertFalse(m.covered(dict(self.row, VulnerabilityID=cve)))
        for field, value in (("PkgName", "other"), ("InstalledVersion", "0.8.0-0ubuntu3"), ("PkgID", "other@" + m.VERSION), ("PkgIdentifier", {"PURL": m.PURL.replace("amd64", "arm64")})):
            self.assertFalse(m.covered(dict(self.row, VulnerabilityID=next(iter(m.FIXED)), **{field: value})))

    def test_unrelated_finding_cannot_be_dropped_or_rewritten(self):
        native = copy.deepcopy(self.native); native["Results"][0]["Vulnerabilities"] = []
        with self.assertRaises(ValueError): m.output(self.raw, native)
        native = copy.deepcopy(self.native); native["Results"][0]["Vulnerabilities"][0]["Severity"] = "LOW"
        with self.assertRaises(ValueError): m.output(self.raw, native)

    def test_package_inventory_and_scan_identity_cannot_drift(self):
        native = copy.deepcopy(self.native); native["Results"][0]["Packages"][0]["Version"] = "forged"
        with self.assertRaises(ValueError): m.output(self.raw, native)
        native = copy.deepcopy(self.native); native["Metadata"]["ImageID"] = "sha256:" + "c" * 64
        with self.assertRaises(ValueError): m.output(self.raw, native)


if __name__ == "__main__":
    unittest.main(verbosity=2)
