#!/usr/bin/env python3
"""Publish a signed FieldSight APK as the in-app update for one stage.

    py -3 tools/release_apk.py --stage prod --apk path/to/fieldsight-PROD-<sha>.apk --notes "..."
    py -3 tools/release_apk.py --stage test --apk path/to/fieldsight-dev-<sha>.apk --dry-run

What devices do with it: every signed-in device on that stage asks GET /api/org/app/latest (at sign-in
and every 6 h), downloads the APK, checks its SHA-256, package, version and signing certificate, and
offers it to install. So everything that could send the wrong build to twenty devices at once is
checked HERE, before anything is uploaded:

  - the package is the stage's flavour (prod -> com.benzn.grandtime, test -> com.benzn.grandtime.dev);
  - the APK is signed with the release certificate every installed build carries;
  - the dex talks to that stage's gateway and not the other one;
  - its versionCode is higher than the build already published.

Upload order: the APK first, its size checked in S3, and only then latest.json -- a device never sees
a manifest naming an APK that is not there. Uses the fieldsight-deployer AWS profile.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile

SDK = os.environ.get("ANDROID_SDK_ROOT", r"C:/Users/camil/AppData/Local/Android/Sdk")
BUILD_TOOLS = os.path.join(SDK, "build-tools", "36.1.0")
JAVA_HOME = os.environ.get("JAVA_HOME", r"C:/Program Files/Android/Android Studio/jbr")
RELEASE_CERT_SHA256 = "a3498366e7066a4ebd2ede7167603244b7e5cf41389e9f9e6f4334d8c5db87a6"

STAGES = {
    "prod": {"package": "com.benzn.grandtime", "bucket": "fieldsight-data-509194952652",
             "gateway": "ys94qy2tk0", "other_gateway": "wdsgobb7b0"},
    "test": {"package": "com.benzn.grandtime.dev", "bucket": "fieldsight-data-test-509194952652",
             "gateway": "wdsgobb7b0", "other_gateway": "ys94qy2tk0"},
}
MANIFEST_KEY = "app-releases/latest.json"
AWS = ["aws", "--profile", "fieldsight-deployer", "--region", "ap-southeast-2"]


def fail(msg):
    print(f"REFUSED: {msg}")
    sys.exit(1)


def run(cmd, **kw):
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    env["PATH"] = os.path.join(JAVA_HOME, "bin") + os.pathsep + env.get("PATH", "")
    return subprocess.run(cmd, capture_output=True, text=True, env=env, **kw)


def badging(apk):
    out = run([os.path.join(BUILD_TOOLS, "aapt.exe" if os.name == "nt" else "aapt"), "dump", "badging", apk])
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", out.stdout)
    if not m:
        fail(f"aapt could not read {apk}: {out.stderr.strip()[:200]}")
    return m.group(1), int(m.group(2)), m.group(3)


def signer_sha256(apk):
    tool = os.path.join(BUILD_TOOLS, "apksigner.bat" if os.name == "nt" else "apksigner")
    out = run([tool, "verify", "--print-certs", apk], shell=(os.name == "nt"))
    digests = re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", out.stdout)
    if out.returncode != 0 or not digests:
        fail(f"apksigner did not verify {apk}: {(out.stderr or out.stdout).strip()[:200]}")
    return set(digests)


def dex_mentions(apk, needle):
    with zipfile.ZipFile(apk) as z:
        return any(needle.encode() in z.read(n) for n in z.namelist() if re.match(r"classes\d*\.dex$", n))


def published(bucket):
    out = run(AWS + ["s3", "cp", f"s3://{bucket}/{MANIFEST_KEY}", "-"])
    if out.returncode != 0:
        if "Not Found" in out.stderr or "NoSuchKey" in out.stderr or "404" in out.stderr:
            return None
        fail(f"could not read the published manifest: {out.stderr.strip()[:300]}")
    return json.loads(out.stdout)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage", required=True, choices=sorted(STAGES))
    ap.add_argument("--apk", required=True)
    ap.add_argument("--notes", default="")
    ap.add_argument("--min-version-code", type=int, default=0,
                    help="devices below this versionCode see the update as required")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    stage = STAGES[args.stage]

    if not os.path.isfile(args.apk):
        fail(f"no such file: {args.apk}")
    package, version_code, version_name = badging(args.apk)
    if package != stage["package"]:
        fail(f"package is {package}; the {args.stage} stage serves {stage['package']}")
    certs = signer_sha256(args.apk)
    if certs != {RELEASE_CERT_SHA256}:
        fail(f"signed by {sorted(certs)}, not the release certificate {RELEASE_CERT_SHA256[:12]}...")
    if not dex_mentions(args.apk, stage["gateway"]) or dex_mentions(args.apk, stage["other_gateway"]):
        fail(f"the APK does not talk only to the {args.stage} gateway {stage['gateway']}")
    if not 0 <= args.min_version_code <= version_code:
        fail(f"--min-version-code must be between 0 and {version_code}")

    current = published(stage["bucket"])
    if current and int(current.get("versionCode", 0)) >= version_code:
        fail(f"versionCode {version_code} is not higher than the published {current.get('versionCode')} "
             f"({current.get('versionName')})")

    with open(args.apk, "rb") as fh:
        sha256 = hashlib.sha256(fh.read()).hexdigest()
    size = os.path.getsize(args.apk)
    apk_key = f"app-releases/{version_code}/{os.path.basename(args.apk)}"
    manifest = {
        "versionCode": version_code, "versionName": version_name,
        "minVersionCode": args.min_version_code, "sha256": sha256, "sizeBytes": size,
        "apkKey": apk_key, "notes": args.notes,
    }
    print(f"stage      {args.stage}  (s3://{stage['bucket']})")
    print(f"package    {package}  {version_name} ({version_code})")
    print(f"signer     {RELEASE_CERT_SHA256[:12]}...  gateway {stage['gateway']}")
    print(f"replaces   {current.get('versionName') + ' (' + str(current.get('versionCode')) + ')' if current else 'nothing published yet'}")
    print(f"apk        {apk_key}  {size} bytes  sha256 {sha256}")
    print(f"manifest   {json.dumps(manifest)}")
    if args.dry_run:
        print("DRY RUN: all checks passed; nothing uploaded")
        return

    up = run(AWS + ["s3", "cp", args.apk, f"s3://{stage['bucket']}/{apk_key}",
                    "--content-type", "application/vnd.android.package-archive", "--only-show-errors"])
    if up.returncode != 0:
        fail(f"APK upload failed: {up.stderr.strip()[:300]}")
    head = run(AWS + ["s3api", "head-object", "--bucket", stage["bucket"], "--key", apk_key,
                      "--query", "ContentLength", "--output", "text"])
    if head.returncode != 0 or head.stdout.strip() != str(size):
        fail(f"uploaded APK size is {head.stdout.strip() or head.stderr.strip()}, expected {size}; manifest NOT written")

    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as tmp:
        json.dump(manifest, tmp)
        tmp_path = tmp.name
    try:
        mf = run(AWS + ["s3", "cp", tmp_path, f"s3://{stage['bucket']}/{MANIFEST_KEY}",
                        "--content-type", "application/json", "--cache-control", "no-cache", "--only-show-errors"])
    finally:
        os.unlink(tmp_path)
    if mf.returncode != 0:
        fail(f"manifest upload failed (the APK is uploaded but not published): {mf.stderr.strip()[:300]}")
    if published(stage["bucket"]) != manifest:
        fail("the manifest read back does not match what was written")
    print(f"PUBLISHED {version_name} ({version_code}) to {args.stage}. Devices pick it up at sign-in or within 6 h.")


if __name__ == "__main__":
    main()
