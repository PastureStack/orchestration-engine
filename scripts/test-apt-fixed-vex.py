#!/usr/bin/env python3
"""Scoped controls for the native APT fixed-VEX evidence boundary."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parent.parent


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


OWNER = module("apt_fixed_owner", "scripts/check-apt-fixed-vex.py")
POLICY = module("release_policy", ".github/scripts/enforce-runtime-vulnerabilities.py")


class AptFixedVexTest(unittest.TestCase):
    def setUp(self):
        self.document = json.loads((ROOT / "security/ubuntu-apt-fixed.openvex.json").read_bytes())
        self.manifest = (ROOT / "security/ubuntu-apt-fixed.sha256").read_text()
        self.inspect = [dict(Id="sha256:" + "a" * 64, Architecture="amd64", Os="linux")]
        packages = [dict(Name=n, ID=n + "@" + OWNER.VERSION, Version=OWNER.VERSION, Arch="amd64",
                         SrcName="apt", SrcVersion=OWNER.VERSION, Identifier=dict(PURL=p)) for n, p in OWNER.PURLS.items()]
        findings = [dict(VulnerabilityID=OWNER.CVE, PkgName=n, PkgID=n + "@" + OWNER.VERSION,
                         InstalledVersion=OWNER.VERSION, Severity="HIGH", PkgIdentifier=dict(PURL=p)) for n, p in OWNER.PURLS.items()]
        self.raw = dict(SchemaVersion=2, ArtifactName="synthetic-owned-dapper", ArtifactType="container_image",
                        Metadata=dict(ImageID=self.inspect[0]["Id"], OS=dict(Family="ubuntu", Name="26.04")),
                        Results=[dict(Target="Ubuntu 26.04", Class="os-pkgs", Type="ubuntu", Packages=packages, Vulnerabilities=findings)])
        self.dpkg = "package\tversion\n" + "".join(n + "\t" + OWNER.VERSION + "\n" for n in OWNER.PURLS)

    def inputs(self, **replace):
        values = dict(document=self.document, manifest=self.manifest, dpkg=self.dpkg,
                      inspect=self.inspect, raw=self.raw, installed=self.manifest)
        values.update(replace)
        return OWNER.input_guard(**values)

    def test_exact_official_pair_and_all_elf_bytes_bind_one_image(self):
        self.assertEqual(self.inspect[0]["Id"], self.inputs())
        native = copy.deepcopy(self.raw)
        native["Results"][0]["Vulnerabilities"] = []
        self.assertEqual(2, OWNER.output_guard(self.raw, native))
        for count in (0, 1):
            raw = copy.deepcopy(self.raw)
            raw["Results"][0]["Vulnerabilities"] = raw["Results"][0]["Vulnerabilities"][:count]
            self.assertEqual(count, OWNER.output_guard(raw, native))

    def test_wrong_version_package_arch_distro_or_cve_cannot_expand_subjects(self):
        for old, new in (("3.2.1ubuntu0.1", "3.2.0"), ("apt@", "other@"), ("amd64", "arm64"),
                         ("ubuntu-26.04", "ubuntu-24.04")):
            with self.subTest(subject=new):
                document = copy.deepcopy(self.document)
                document["statements"][0]["products"][0]["@id"] = document["statements"][0]["products"][0]["@id"].replace(old, new)
                with self.assertRaises(ValueError): self.inputs(document=document)
        for change in (dict(vulnerability=dict(name="CVE-OTHER")), dict(status="not_affected"),
                       dict(products=[dict(**{"@id": "pkg:deb/ubuntu/apt@*"})])):
            document = copy.deepcopy(self.document)
            document["statements"][0].update(change)
            with self.assertRaises(ValueError): self.inputs(document=document)

    def test_native_old_version_missing_pair_or_changed_elf_fails_closed(self):
        for value in (self.dpkg.replace(OWNER.VERSION, "3.2.0"), "package\tversion\napt\t" + OWNER.VERSION + "\n"):
            with self.assertRaises(ValueError): self.inputs(dpkg=value)
        for value in (self.manifest.replace(self.manifest[:64], "0" * 64, 1), "", self.manifest + self.manifest.splitlines()[0] + "\n"):
            with self.assertRaises(ValueError): self.inputs(installed=value)
        raw = copy.deepcopy(self.raw)
        raw["Results"][0]["Packages"][0]["Identifier"]["PURL"] = OWNER.PURLS["apt"].replace("amd64", "arm64")
        with self.assertRaises(ValueError): self.inputs(raw=raw)

    def test_other_package_cve_version_arch_and_distro_stay_uncovered(self):
        original = self.raw["Results"][0]["Vulnerabilities"][0]
        for field, value in (("VulnerabilityID", "CVE-OTHER"), ("PkgName", "other"), ("InstalledVersion", "3.2.0"),
                             ("PkgID", "apt@3.2.0")):
            changed = copy.deepcopy(original)
            changed[field] = value
            self.assertFalse(OWNER.covered(changed))
        for old, new in (("amd64", "arm64"), ("ubuntu-26.04", "ubuntu-24.04"), (OWNER.VERSION, "3.2.0")):
            changed = copy.deepcopy(original)
            changed["PkgIdentifier"]["PURL"] = changed["PkgIdentifier"]["PURL"].replace(old, new)
            self.assertFalse(OWNER.covered(changed))

    def test_native_vex_cannot_remove_other_high_or_change_package_inventory(self):
        raw = copy.deepcopy(self.raw)
        other = dict(VulnerabilityID="CVE-OTHER", PkgName="other", PkgID="other@1", InstalledVersion="1", Severity="HIGH")
        raw["Results"][0]["Vulnerabilities"].append(other)
        native = copy.deepcopy(raw)
        native["Results"][0]["Vulnerabilities"] = [other]
        self.assertEqual(2, OWNER.output_guard(raw, native))
        self.assertEqual(1, len(POLICY.violations(native, dict(repository="PastureStack/orchestration-engine", unfixed=[]), "PastureStack/orchestration-engine")))
        native["Results"][0]["Vulnerabilities"] = []
        with self.assertRaises(ValueError): OWNER.output_guard(raw, native)
        native = copy.deepcopy(self.raw)
        native["Results"][0]["Packages"] = []
        with self.assertRaises(ValueError): OWNER.output_guard(self.raw, native)
        native = copy.deepcopy(self.raw)
        native["Metadata"]["ImageID"] = "sha256:" + "b" * 64
        with self.assertRaises(ValueError): OWNER.output_guard(self.raw, native)

    def test_package_permutation_and_documented_graph_metadata_keep_all_content_and_counts(self):
        raw = copy.deepcopy(self.raw)
        java = dict(Target="Java", Class="lang-pkgs", Type="jar", Packages=[
            dict(Name="example:a", Version="1", FilePath="a.jar", Identifier=dict(PURL="pkg:maven/example/a@1", UID="11"), Extra=dict(value=1)),
            dict(Name="example:b", Version="2", FilePath="b.jar", Identifier=dict(PURL="pkg:maven/example/b@2", UID="22"), Extra=dict(value=2))])
        purl = "pkg:pypi/example@1"
        python = dict(Target="Python", Class="lang-pkgs", Type="python-pkg", Packages=[
            dict(ID="example@1", Name="example", Version="1", AnalyzedBy="sbom", Identifier=dict(PURL=purl, BOMRef=purl, UID="33"), Licenses=["MIT"])])
        raw["Results"].extend([java, python])
        native = copy.deepcopy(raw)
        native["Results"][0]["Vulnerabilities"] = []
        native["Results"][1]["Packages"].reverse()
        for package in native["Results"][1]["Packages"]:
            package["ID"] = package["Name"] + "@" + package["Version"]
        native["Results"][2]["Packages"][0]["Identifier"]["UID"] = "44"
        self.assertEqual(2, OWNER.output_guard(raw, native))
        for field, value in (("Extra", dict(value=99)), ("FilePath", "other.jar"), ("Version", "9"), ("ID", "wrong-id")):
            changed = copy.deepcopy(native)
            changed["Results"][1]["Packages"][0][field] = value
            with self.assertRaises(ValueError): OWNER.output_guard(raw, changed)
        changed = copy.deepcopy(native)
        changed["Results"][1]["Packages"].append(copy.deepcopy(changed["Results"][1]["Packages"][0]))
        with self.assertRaises(ValueError): OWNER.output_guard(raw, changed)
        changed = copy.deepcopy(native)
        other = copy.deepcopy(changed["Results"][2]["Packages"][0])
        other["Name"] = "other"
        other["Identifier"]["PURL"] = other["Identifier"]["BOMRef"] = "pkg:pypi/other@1"
        changed["Results"][2]["Packages"].append(other)
        with self.assertRaisesRegex(ValueError, "graph UID collision"): OWNER.output_guard(raw, changed)
        changed = copy.deepcopy(native)
        changed["Results"][0]["Packages"][0]["Identifier"]["UID"] = "55"
        with self.assertRaises(ValueError): OWNER.output_guard(raw, changed)
        raw["Results"][2]["Packages"][0]["AnalyzedBy"] = "python-package"
        changed = copy.deepcopy(raw)
        changed["Results"][0]["Vulnerabilities"] = []
        changed["Results"][2]["Packages"][0]["Identifier"]["UID"] = "44"
        with self.assertRaises(ValueError): OWNER.output_guard(raw, changed)

    def test_workflow_keeps_raw_then_native_vex_then_original_pip_owner(self):
        workflow = (ROOT / ".github/workflows/security-release-gate.yml").read_text()
        raw = workflow.index("--output /work/evidence/dapper-image-vulnerabilities.raw.json")
        inputs = workflow.index("scripts/check-apt-fixed-vex.py", raw)
        native = workflow.index("--vex /work/evidence/dapper-apt-fixed.openvex.json", inputs)
        pip_raw = workflow.index("python3 tools/pip-security-overlay/filter-pip-vendor-findings.py", native)
        pip_native = workflow.index("python3 tools/pip-security-overlay/filter-pip-vendor-findings.py", pip_raw + 1)
        proof = workflow.index("python3 scripts/check-apt-fixed-vex.py", pip_native)
        rust_inputs = workflow.index("python3 scripts/check-rust-coreutils-fixed-vex.py", proof)
        rust_native = workflow.index("--vex /work/evidence/dapper-rust-fixed.openvex.json", rust_inputs)
        rust_proof = workflow.index("--proof evidence/dapper-rust-fixed-vex-proof.json", rust_native)
        final = workflow.index("cp evidence/dapper-image-vulnerabilities.apt-rust-fixed-pip-qualified.json", rust_proof)
        self.assertLess(raw, inputs)
        self.assertLess(inputs, native)
        self.assertLess(native, pip_raw)
        self.assertLess(pip_raw, pip_native)
        self.assertLess(pip_native, proof)
        self.assertLess(proof, rust_inputs)
        self.assertLess(rust_inputs, rust_native)
        self.assertLess(rust_native, rust_proof)
        self.assertLess(rust_proof, final)
        self.assertIn("--raw evidence/dapper-image-vulnerabilities.raw.json", workflow[pip_raw:pip_native])
        self.assertIn("--output evidence/dapper-image-vulnerabilities.raw-pip-qualified.json", workflow[pip_raw:pip_native])
        self.assertIn("--raw evidence/dapper-image-vulnerabilities.apt-fixed.json", workflow[pip_native:proof])
        self.assertIn("--output evidence/dapper-image-vulnerabilities.apt-fixed-pip-qualified.json", workflow[pip_native:proof])
        for qualified in (workflow[pip_raw:pip_native], workflow[pip_native:proof]):
            self.assertIn("--proof evidence/dapper-pip-vendor-proof.json", qualified)
            self.assertIn('--subject "$dapper_subject"', qualified)
        self.assertIn("--raw evidence/dapper-image-vulnerabilities.raw-pip-qualified.json", workflow[proof:final])
        self.assertIn("--native evidence/dapper-image-vulnerabilities.apt-fixed-pip-qualified.json", workflow[proof:rust_inputs])
        self.assertIn("--skip-db-update --skip-java-db-update --skip-vex-repo-update", workflow[inputs:pip_raw])
        for original in ("raw.json", "apt-fixed.json"):
            self.assertEqual(1, workflow[raw:final].count("--output /work/evidence/dapper-image-vulnerabilities." + original))
            self.assertNotIn("--output evidence/dapper-image-vulnerabilities." + original, workflow[raw:final])
        self.assertNotIn("--ignore-unfixed", workflow[raw:final])
        self.assertNotIn("--ignore-status", workflow[raw:final])


if __name__ == "__main__":
    unittest.main()
