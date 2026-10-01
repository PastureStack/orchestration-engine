#!/usr/bin/env python3
"""Exercise the real POM/JAR Jackson floor blocks without Maven or network."""
import ast
import io
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
SOURCE = (REPO / "scripts/check-cattle-dependency-hygiene").read_text(encoding="utf-8")
BLOCKS = dict(re.findall(
    r"(?ms)^(jackson_security_floor_\w+)=\$\(\n\s*python3 - <<'PY'\n(.*?)\nPY\n\)", SOURCE))
POM = (REPO / "code/meta-parent/pom.xml").read_text(encoding="utf-8")


def findings(kind, root):
    result = subprocess.run([sys.executable, "-c", BLOCKS[kind]], cwd=root,
                            capture_output=True, text=True, check=True)
    return result.stdout.strip()


class JacksonSecurityFloorsTest(unittest.TestCase):
    def test_multi_release_namespaces_reject_foreign_classes_but_allow_module_descriptors(self):
        source = (REPO / "scripts/check-cattle-runtime-jar-uniqueness").read_text(encoding="utf-8")
        program = ast.parse(source.split("<<'PY'\n", 1)[1].rsplit("\nPY", 1)[0])
        nodes = [node for node in program.body
                 if isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef))
                 or isinstance(node, ast.Assign) and any(
                     isinstance(name, ast.Name) and name.id == "reviewed_dual_namespace_artifacts"
                     for name in node.targets)]
        namespace = {}
        exec(compile(ast.Module(body=nodes, type_ignores=[]), "<real-namespace-gate>", "exec"), namespace)
        payloads = {}

        def jar(name, entries):
            output = io.BytesIO()
            with zipfile.ZipFile(output, "w") as archive:
                for entry in entries:
                    archive.writestr(entry, b"fixture")
            payloads[name] = output.getvalue()
            return Path(name)

        namespace["ZipFile"] = lambda path: zipfile.ZipFile(io.BytesIO(payloads[path.name]))
        check = namespace["reviewed_dual_namespace_pair"]
        for artifact in ("jackson-core", "jackson-databind"):
            suffix = "core" if artifact == "jackson-core" else "databind"
            for major, version, prefix in ((2, "2.22.3", "com/fasterxml"), (3, "3.2.3", "tools")):
                with self.subTest(artifact=artifact, major=major):
                    own = f"{prefix}/jackson/{suffix}/Reviewed.class"
                    other_prefix = "tools" if major == 2 else "com/fasterxml"
                    other = f"{other_prefix}/jackson/{suffix}/Reviewed.class"
                    other_version = "3.2.3" if major == 2 else "2.22.3"
                    first = jar(f"{artifact}-{version}.jar", [own, f"META-INF/versions/25/{own}",
                                                             "module-info.class"])
                    second = jar(f"{artifact}-{other_version}.jar", [other,
                                                                     "META-INF/versions/9/module-info.class"])
                    self.assertTrue(check(artifact, [first, second]))
                    for foreign in (other, f"META-INF/versions/9/{other}", f"META-INF/versions/25/{other}"):
                        jar(first.name, [own, foreign, "module-info.class"])
                        self.assertFalse(check(artifact, [first, second]), foreign)

    def check_pom(self, text):
        with tempfile.TemporaryDirectory(prefix="engine328-jackson-pom-") as directory:
            root = Path(directory)
            pom = root / "code/meta-parent/pom.xml"
            pom.parent.mkdir(parents=True)
            pom.write_text(text, encoding="utf-8")
            return findings("jackson_security_floor_dependencies", root)

    def check_jars(self, names):
        with tempfile.TemporaryDirectory(prefix="engine328-jackson-jars-") as directory:
            root = Path(directory)
            lib = root / "code/packaging/app/target/app/WEB-INF/lib"
            lib.mkdir(parents=True)
            for name in names:
                (lib / name).touch()
            return findings("jackson_security_floor_jars", root)

    def test_pom_patched_lines_accept_and_older_or_qualified_versions_reject(self):
        self.assertEqual(set(BLOCKS), {
            "jackson_security_floor_dependencies", "jackson_security_floor_jars"})
        self.assertEqual(self.check_pom(POM), "")
        for name, current, rejected in [
                ("jackson.version", "2.22.3", "2.22.2"),
                ("webauthn.jackson.version", "3.2.3", "3.2.2"),
                ("jackson.version", "2.22.3", "2.22.3-SNAPSHOT"),
                ("webauthn.jackson.version", "3.2.3", "3.2.3-RC1")]:
            with self.subTest(property=name, rejected=rejected):
                before = f"<{name}>{current}</{name}>"
                self.assertIn(before, POM)
                self.assertTrue(self.check_pom(POM.replace(before, f"<{name}>{rejected}</{name}>")))

    def test_packaged_patched_lines_and_patchless_annotations_accept(self):
        self.assertEqual(self.check_jars([
            "jackson-core-2.22.3.jar", "jackson-databind-2.22.3.jar",
            "jackson-core-3.2.3.jar", "jackson-databind-3.2.3.jar",
            "jackson-dataformat-cbor-3.2.3.jar", "jackson-annotations-2.22.jar"]), "")

    def test_packaged_old_lines_qualifiers_and_patched_annotations_reject(self):
        for name in ["jackson-core-2.22.2.jar", "jackson-databind-2.22.2.jar",
                     "jackson-core-3.2.2.jar", "jackson-databind-3.2.2.jar",
                     "jackson-databind-2.22.3-SNAPSHOT.jar", "jackson-annotations-2.22.3.jar"]:
            with self.subTest(jar=name):
                self.assertTrue(self.check_jars([name]))


if __name__ == "__main__":
    unittest.main()
