#!/usr/bin/env python3
"""Fetch the pinned Maven jars Vorv carries (runtime-manifest.json → mavenFiles).

Usage: fetch-maven.py <manifest> <cache-dir> <output-dir>

A file is reused only when its sha256 matches the manifest; a download that
does not match is refused and nothing is copied, so a swapped artifact can
never reach the phone.
"""
import hashlib
import json
import os
import shutil
import sys
import urllib.request


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main(argv):
    if len(argv) != 4:
        sys.exit("usage: fetch-maven.py <manifest> <cache-dir> <output-dir>")
    manifest, cache, out = argv[1], argv[2], argv[3]
    files = json.load(open(manifest)).get("mavenFiles", [])
    os.makedirs(cache, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    for f in files:
        name = f["as"]
        cached = os.path.join(cache, name)
        if not os.path.isfile(cached) or digest(cached) != f["sha256"]:
            print("fetching " + name)
            part = cached + ".part"
            urllib.request.urlretrieve(f["url"], part)
            got = digest(part)
            if got != f["sha256"]:
                os.remove(part)
                sys.exit("REFUSED: %s has sha256 %s, manifest says %s" % (name, got, f["sha256"]))
            os.replace(part, cached)
        shutil.copyfile(cached, os.path.join(out, name))
        print("maven %s verified (%d bytes)" % (name, os.path.getsize(cached)))


if __name__ == "__main__":
    main(sys.argv)
