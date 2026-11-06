"""Enforce release findings; only exact, reviewed unfixed low/medium entries may pass."""
import argparse
import datetime
import json
from pathlib import Path


def violations(report, ledger, repository, *, phase=None, today=None):
    if (not isinstance(report, dict) or report.get("SchemaVersion") != 2
            or not isinstance(report.get("Results"), list) or not report["Results"]):
        raise ValueError("missing or malformed Trivy scan results")
    for result in report["Results"]:
        if not isinstance(result, dict) or not isinstance(result.get("Target"), str):
            raise ValueError("malformed Trivy result")
        for key in ("Vulnerabilities", "Secrets"):
            if key in result and result[key] is not None and not isinstance(result[key], list):
                raise ValueError("malformed Trivy findings")
    if ledger.get("repository") != repository:
        raise ValueError("allowlist repository mismatch")
    current_date = today or datetime.datetime.now(datetime.timezone.utc).date()
    reviewed = {}
    for entry in ledger.get("unfixed", []):
        key = (entry["id"], entry["package"], entry["version"], entry.get("purl", ""))
        if key in reviewed:
            raise ValueError("duplicate reviewed finding")
        if (entry.get("severity") not in ("LOW", "MEDIUM")
                or entry.get("status") != "no_official_fix"
                or not entry.get("officialEvidence", "").startswith("https://")
                or not entry.get("reason") or not entry.get("checkedAt")):
            raise ValueError("incomplete official no-fix evidence")
        if any(field in entry for field in ("phase", "imageScope", "reviewBy")):
            if (entry.get("phase") != "builder"
                    or entry.get("imageScope") != "pasturestack/orchestration-engine-dapper"):
                raise ValueError("incomplete Dapper build-only review scope")
            try:
                review_by = datetime.date.fromisoformat(entry["reviewBy"])
                checked_at = datetime.date.fromisoformat(entry["checkedAt"])
            except (KeyError, TypeError, ValueError) as error:
                raise ValueError("invalid scoped review dates") from error
            if review_by < checked_at:
                raise ValueError("review deadline precedes official check")
        reviewed[key] = entry
    errors = []
    for result in report.get("Results") or []:
        if result.get("Secrets"):
            errors.append("secret findings remain")
        for finding in result.get("Vulnerabilities") or []:
            severity = finding.get("Severity", "UNKNOWN")
            key = (finding.get("VulnerabilityID"), finding.get("PkgName"),
                   finding.get("InstalledVersion"),
                   (finding.get("PkgIdentifier") or {}).get("PURL", ""))
            entry = reviewed.get(key)
            fixed = str(finding.get("FixedVersion") or "").strip()
            scope_matches = (entry is None or "reviewBy" not in entry or (
                phase == entry["phase"] and report.get("ArtifactType") == "container_image"
                and report.get("ArtifactName", "").partition(":")[0] == entry["imageScope"]
                and current_date <= datetime.date.fromisoformat(entry["reviewBy"])))
            if (severity not in ("LOW", "MEDIUM") or fixed
                    or entry is None or entry["severity"] != severity or not scope_matches):
                errors.append("%s %s@%s severity=%s fixed=%s" % (
                    key[0], key[1], key[2], severity, fixed or "none"))
    return errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", choices=("artifact", "builder"), required=True)
    parser.add_argument("--repository", required=True)
    args = parser.parse_args()
    if args.phase == "artifact":
        report = Path("evidence/cattle-artifact-vulnerabilities.json")
    else:
        report = Path("evidence/dapper-image-vulnerabilities.json")
    failures = violations(json.loads(report.read_text(encoding="utf-8")),
                          json.loads(Path(".github/runtime-unfixed.json").read_text(encoding="utf-8")), args.repository,
                          phase=args.phase)
    if failures:
        raise SystemExit("Release vulnerability policy failed:\n" + "\n".join(failures))
    print("RELEASE_VULNERABILITY_POLICY_OK")
