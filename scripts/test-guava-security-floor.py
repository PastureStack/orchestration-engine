#!/usr/bin/env python3
"""Exercise the production Guava POM/JAR floor blocks without a full build."""
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
SOURCE = (REPO / "scripts/check-cattle-dependency-hygiene").read_text(encoding="utf-8")
BLOCKS = dict(re.findall(
    r"(?ms)^(guava_stable_line_\w+)=\$\(\n\s*python3 - <<'PY'\n(.*?)\nPY\n\)", SOURCE))
POM = (REPO / "code/meta-parent/pom.xml").read_text(encoding="utf-8")


def findings(kind, root):
    result = subprocess.run([sys.executable, "-c", BLOCKS[kind]], cwd=root,
                            capture_output=True, text=True, check=True)
    return result.stdout.strip()


class GuavaSecurityFloorTest(unittest.TestCase):
    def check_pom(self, text):
        with tempfile.TemporaryDirectory(prefix="engine355-guava-pom-") as directory:
            root = Path(directory)
            pom = root / "code/meta-parent/pom.xml"
            pom.parent.mkdir(parents=True)
            pom.write_text(text, encoding="utf-8")
            return findings("guava_stable_line_dependencies", root)

    def check_jar(self, version):
        with tempfile.TemporaryDirectory(prefix="engine355-guava-jar-") as directory:
            root = Path(directory)
            lib = root / "code/packaging/app/target/app/WEB-INF/lib"
            lib.mkdir(parents=True)
            (lib / f"guava-{version}.jar").touch()
            return findings("guava_stable_line_jars", root)

    def test_current_official_jre_patch_passes_both_production_gates(self):
        self.assertEqual(set(BLOCKS), {"guava_stable_line_dependencies", "guava_stable_line_jars"})
        self.assertIn("<version>33.7.2-jre</version>", POM)
        self.assertEqual("", self.check_pom(POM))
        self.assertEqual("", self.check_jar("33.7.2-jre"))

    def test_old_patch_wrong_line_and_qualifiers_are_rejected(self):
        for version in ["33.7.1-jre", "33.7.2-android", "33.7.2-jre-SNAPSHOT", "34.0.0-jre"]:
            with self.subTest(version=version):
                self.assertTrue(self.check_pom(POM.replace("33.7.2-jre", version)))
                self.assertTrue(self.check_jar(version))


if __name__ == "__main__":
    unittest.main()
