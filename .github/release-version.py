"""Materialize release POMs in a disposable checkout; never commit the result."""

import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

SNAPSHOT = "0.0.0-SNAPSHOT"
GROUP = "io.github.maxsumrall.jev4j"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
POMS = (
    "pom.xml",
    "jev4j-core/pom.xml",
    "jev4j-spring-boot-starter/pom.xml",
    "consumer-tests/pom.xml",
    "examples/plain-java/pom.xml",
    "examples/spring-boot-triage/pom.xml",
)


def validate_version(version):
    if not re.fullmatch(r"[2-9][0-9]{3}\.(?:[1-9]|1[0-2])\.[1-9][0-9]*", version):
        raise ValueError("Expected CalVer YYYY.M.N, e.g. 2026.9.1 (no leading zeroes)")


def validate_pom(text, version, tag):
    root = ET.fromstring(text)
    nodes = root.findall("m:version", NS)
    for node in root.findall("m:parent", NS) + root.findall(
        "m:dependencies/m:dependency", NS
    ):
        if node.findtext("m:groupId", namespaces=NS) == GROUP:
            dep_version = node.find("m:version", NS)
            if dep_version is None:
                raise ValueError("Missing internal dependency version")
            if dep_version.text != "${project.version}":
                nodes.append(dep_version)
    if not nodes or any(node.text != version for node in nodes):
        raise ValueError(f"Expected coordinated version {version}")
    for node in root.findall("m:scm/m:tag", NS):
        if node.text != tag:
            raise ValueError(f"Expected SCM tag {tag}")


def prepare(directory, version):
    validate_version(version)
    changes = {}
    for name in POMS:
        path = directory / name
        text = path.read_text()
        validate_pom(text, SNAPSHOT, "HEAD")
        updated = text.replace(
            f"<version>{SNAPSHOT}</version>", f"<version>{version}</version>"
        )
        updated = updated.replace("<tag>HEAD</tag>", f"<tag>v{version}</tag>")
        validate_pom(updated, version, f"v{version}")
        changes[path] = updated
    # Validate all files before writing any; preserve XML formatting and comments.
    for path, text in changes.items():
        path.write_text(text)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    args = parser.parse_args()
    prepare(Path.cwd(), args.version)
