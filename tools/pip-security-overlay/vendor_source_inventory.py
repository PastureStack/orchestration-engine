"""Bind pathless scanner SBOM components to the image's exact pip vendor sources."""
from email.parser import BytesParser
import hashlib
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile


WHEEL_NAME = "pip-26.2.2+pasturestack.1-py3-none-any.whl"
WHEEL_PATHS = {"/usr/share/python-wheels/" + WHEEL_NAME,
               "/usr/lib/python3.14/ensurepip/_bundled/" + WHEEL_NAME,
               "/opt/python-security/" + WHEEL_NAME,
               "/licenses/pip-security/" + WHEEL_NAME}
SBOM_PATHS = {"/usr/local/lib/python3.14/dist-packages/pip/_vendor/bom.cdx.json",
              "/usr/lib/python3/dist-packages/pip/_vendor/bom.cdx.json",
              "/licenses/pip-security/pip-vendor.cdx.json"}
TARGETS = {"msgpack": "1.1.2", "setuptools": "70.3.0"}


def matches(name, version, purl=""):
    normalized = str(name).lower()
    return (normalized in TARGETS and TARGETS[normalized] == version) or any(
        purl == "pkg:pypi/" + package + "@" + expected for package, expected in TARGETS.items())


def matching_sbom(data):
    if data.lstrip().startswith(b"SPDXVersion:"):
        package = None
        for line in data.decode("utf-8").splitlines():
            if line.startswith("PackageName:"):
                package = line.partition(":")[2].strip()
            elif line.startswith("PackageVersion:") and matches(package, line.partition(":")[2].strip()):
                return True
        return False
    try:
        document = json.loads(data)
    except (ValueError, UnicodeError):
        try:
            root = ET.fromstring(data)
        except ET.ParseError:
            return False
        if root.tag.rsplit("}", 1)[-1] != "bom":
            return False
        for node in root.iter():
            if node.tag.rsplit("}", 1)[-1] == "component":
                values = {child.tag.rsplit("}", 1)[-1]: child.text for child in node}
                if matches(values.get("name"), values.get("version"), values.get("purl", "")):
                    return True
        return False
    if not isinstance(document, dict):
        return False
    if document.get("bomFormat") == "CycloneDX":
        pending = list(document.get("components", []))
        primary = document.get("metadata", {}).get("component")
        if isinstance(primary, dict):
            pending.append(primary)
        while pending:
            item = pending.pop()
            if matches(item.get("name"), item.get("version"), item.get("purl", "")):
                return True
            pending.extend(item.get("components", []))
    if document.get("spdxVersion"):
        for item in document.get("packages", []):
            if matches(item.get("name"), item.get("versionInfo")) or any(
                    matches(None, None, ref.get("referenceLocator", "")) for ref in item.get("externalRefs", [])):
                return True
    return False


def matching_distribution(data):
    metadata = BytesParser().parsebytes(data)
    return matches(metadata.get("Name"), metadata.get("Version"))


def inventory(root, wheel_sha, bom_sha):
    root = Path(root)
    sources = []
    def fail_walk(error):
        raise error
    for directory, dirs, files in os.walk(root, followlinks=False, onerror=fail_walk):
        if Path(directory) == root:
            dirs[:] = [name for name in dirs if name not in {"proc", "sys", "dev"}]
        for name in files:
            path = Path(directory, name)
            logical = "/" + path.relative_to(root).as_posix()
            if name in {"METADATA", "PKG-INFO"}:
                if matching_distribution(path.read_bytes()):
                    raise ValueError("Full matching distribution outside pip vendor slice: " + logical)
            elif name.endswith((".json", ".xml", ".spdx")):
                data = path.read_bytes()
                if matching_sbom(data):
                    digest = hashlib.sha256(data).hexdigest()
                    if logical not in SBOM_PATHS or digest != bom_sha:
                        raise ValueError("Unverified matching SBOM: " + logical)
                    sources.append({"path": logical, "kind": "sbom", "sha256": digest})
            elif name.endswith(".whl"):
                with zipfile.ZipFile(path) as archive:
                    matching = False
                    for member in archive.namelist():
                        if member.endswith(("/METADATA", "/PKG-INFO")) and matching_distribution(archive.read(member)):
                            raise ValueError("Full matching distribution wheel: " + logical)
                        if member.endswith((".json", ".xml", ".spdx")) and matching_sbom(archive.read(member)):
                            if member != "pip/_vendor/bom.cdx.json" or hashlib.sha256(archive.read(member)).hexdigest() != bom_sha:
                                raise ValueError("Unverified matching wheel SBOM: " + logical)
                            matching = True
                    if matching:
                        digest = hashlib.sha256(path.read_bytes()).hexdigest()
                        if logical not in WHEEL_PATHS or digest != wheel_sha:
                            raise ValueError("Unverified matching wheel: " + logical)
                        sources.append({"path": logical, "kind": "wheel", "sha256": digest})
    if not any(item["kind"] == "sbom" for item in sources) or not any(item["kind"] == "wheel" for item in sources):
        raise ValueError("Both live SBOM and fixed seed inventory are required")
    return {"complete": True, "noForeignMatchingDistribution": True,
            "expectedBomSha256": bom_sha, "sources": sorted(sources, key=lambda item: item["path"])}
