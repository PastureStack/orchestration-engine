#!/usr/bin/env python3
"""Reject development-only files from an Engine release archive."""

import io
import sys
import zipfile
from pathlib import PurePosixPath


class InvalidReleaseArtifact(Exception):
    pass


def check_artifact(path):
    try:
        with zipfile.ZipFile(path) as artifact:
            entries = artifact.namelist()
            for entry in entries:
                if entry.startswith("WEB-INF/lib/cattle-dev-") and entry.endswith(".jar"):
                    raise InvalidReleaseArtifact(
                        f"ENGINE_RELEASE_DEV_JAR_FORBIDDEN entry={entry}"
                    )
                if PurePosixPath(entry).name == "dev-defaults.properties":
                    raise InvalidReleaseArtifact(
                        f"ENGINE_RELEASE_DEV_DEFAULTS_FORBIDDEN entry={entry}"
                    )

            for entry in entries:
                if not entry.endswith(".jar"):
                    continue
                try:
                    with zipfile.ZipFile(io.BytesIO(artifact.read(entry))) as library:
                        for nested_entry in library.namelist():
                            if PurePosixPath(nested_entry).name == "dev-defaults.properties":
                                raise InvalidReleaseArtifact(
                                    "ENGINE_RELEASE_DEV_DEFAULTS_FORBIDDEN "
                                    f"entry={entry}!/{nested_entry}"
                                )
                except zipfile.BadZipFile as exc:
                    raise InvalidReleaseArtifact(
                        f"ENGINE_RELEASE_INVALID_LIBRARY entry={entry}"
                    ) from exc
    except zipfile.BadZipFile as exc:
        raise InvalidReleaseArtifact("ENGINE_RELEASE_INVALID_ARCHIVE") from exc


if __name__ == "__main__":
    try:
        check_artifact(sys.argv[1])
    except InvalidReleaseArtifact as exc:
        print(exc, file=sys.stderr)
        sys.exit(1)
