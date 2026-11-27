#!/usr/bin/env python3
"""Allocate a Release-workflow identity and publish without replacing historical artifacts."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

# The rolling `preview` release shipped version code 2; every numbered release must be above it.
LEGACY_CODE = 2
MAX_CODE = 2_100_000_000
MARKER = re.compile(r"<!-- capullo-publication (\{[^\n]+\}) -->")
PACKAGE = "tech.capullo.tubecapullo"


def identity(number):
    """The Release workflow's run number is the version code and the last part of the name."""
    sequence = int(number)
    if sequence <= LEGACY_CODE or sequence > MAX_CODE:
        raise ValueError("Publication sequence is outside the usable version-code range")
    return {"code": sequence, "name": f"0.2.{sequence}"}


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def digest(path):
    with open(path, "rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def tools():
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    directories = sorted(
        sdk.glob("build-tools/*"),
        key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)),
    )
    return directories[-1]


def apk_identity(apk, expected):
    directory = tools()
    badging = run(str(directory / "aapt2"), "dump", "badging", str(apk))
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    if not match or (match[1], int(match[2]), match[3]) != (PACKAGE, expected["code"], expected["name"]):
        raise ValueError("APK manifest does not match the publication identity")
    return certificate_of(apk)


def certificate_of(apk):
    certificates = run(str(tools() / "apksigner"), "verify", "--print-certs", str(apk))
    # The label before "certificate" depends on the build-tools version: 36.0.0 prints
    # "Signer #1 certificate SHA-256 digest", 37.0.0 prints "V2 Signer: certificate SHA-256
    # digest" for the same APK (Release run 34, which stopped here). One signing identity is one
    # distinct digest, whatever the label and however many signature schemes report it.
    signers = {
        found.lower()
        for found in re.findall(r"certificate SHA-256 digest: ([a-fA-F0-9]{64})\b", certificates)
    }
    if len(signers) != 1:
        raise ValueError(f"Expected one verified signing certificate, found {len(signers)}")
    return signers.pop()


def metadata(release):
    match = MARKER.search(release.get("body") or "")
    if match:
        return json.loads(match[1])
    raise ValueError(f"Release {release['tag_name']} has no version-code metadata; establish its identity before publishing")


def validate_publication(current, releases):
    """A rerun may finish its own draft, never overtake a newer published identity."""
    existing = None
    for release in releases:
        info = metadata(release)
        if release["tag_name"] == current["name"]:
            if info != current:
                raise ValueError("This tag already identifies a different commit, APK or certificate")
            existing = release
        elif info["code"] >= current["code"]:
            raise ValueError("A newer or equal version code already exists; use a new Release run")
        if info.get("certificate") and info["certificate"] != current["certificate"]:
            raise ValueError("Signing certificate differs from an existing publication")
    return existing


def publish(number, sha, repo, artifacts):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Publication target must be an exact commit SHA")
    current = identity(number)
    apks = list(Path(artifacts).rglob("*.apk"))
    if len(apks) != 1:
        raise ValueError("Expected exactly one release APK")
    apk = apks[0]
    current.update(sha=sha, sha256=digest(apk), certificate=apk_identity(apk, current))
    pages = json.loads(run("gh", "api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100"))
    releases = [release for page in pages for release in page]
    existing = validate_publication(current, releases)
    tag = current["name"]
    # Even a tag with no release must not be silently adopted at a different commit.
    refs = run("git", "ls-remote", "origin", f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}")
    if refs:
        rows = dict(line.split()[::-1] for line in refs.splitlines())
        target = rows.get(f"refs/tags/{tag}^{{}}", rows.get(f"refs/tags/{tag}"))
        if target != sha:
            raise ValueError("Existing tag points at another commit")
    asset_name = f"capullo-tube-{tag}-vc{current['code']}.apk"
    if existing:
        assets = existing.get("assets", [])
        if assets:
            if len(assets) != 1 or assets[0]["name"] != asset_name:
                raise ValueError("Unexpected release assets; refusing to replace them")
            with tempfile.TemporaryDirectory() as tmp:
                run("gh", "release", "download", tag, "--repo", repo, "--pattern", asset_name, "--dir", tmp)
                if digest(Path(tmp) / asset_name) != current["sha256"]:
                    raise ValueError("Published APK checksum differs; refusing to overwrite it")
            if not existing["draft"]:
                print(f"Already published unchanged: {tag}")
                return
        elif not existing["draft"]:
            raise ValueError("Published release has no APK; refusing to alter a published identity")
    else:
        notes = (
            f"Preview from {sha}. Android versionCode {current['code']}. "
            "Signed with the existing preview key.\n\n"
            f"<!-- capullo-publication {json.dumps(current, sort_keys=True)} -->"
        )
        run("gh", "release", "create", tag, "--repo", repo, "--target", sha,
            "--draft", "--prerelease", "--title", tag, "--notes", notes)
    if not existing or not existing.get("assets"):
        with tempfile.TemporaryDirectory() as tmp:
            named = Path(tmp) / asset_name
            shutil.copyfile(apk, named)
            run("gh", "release", "upload", tag, str(named), "--repo", repo)
    run("gh", "release", "edit", tag, "--repo", repo, "--draft=false")
    print(f"Published {tag}, versionCode {current['code']}, commit {sha}")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "identity":
        for key, value in identity(sys.argv[2]).items():
            print(f"{key}={value}")
    elif len(sys.argv) == 6 and sys.argv[1] == "publish":
        publish(*sys.argv[2:])
    else:
        raise SystemExit("Usage: preview_release.py identity NUMBER | publish NUMBER SHA REPO ARTIFACTS")
