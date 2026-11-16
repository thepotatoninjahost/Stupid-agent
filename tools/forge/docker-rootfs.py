#!/usr/bin/env python3
"""Assemble a Debian root filesystem from the official registry image.

Debian does not publish a proot-friendly arm64 tarball the way Alpine does, so
the base image is taken from Docker Hub instead: `library/debian:bookworm-slim`.
Pulling by *digest* rather than by tag is what makes this reproducible — a tag
can be repointed, a digest cannot. Every layer blob is hashed before it is
written, so a truncated or substituted download fails here, on the build machine,
rather than as a strange failure inside the guest.

    docker-rootfs.py <manifest.json> <out.tar>

The manifest's linuxSystem.rootfs.imageManifest and .layer are the pins.
"""
import hashlib
import io
import json
import sys
import tarfile
import urllib.request

REPO = "library/debian"
SKIP_PREFIXES = ("dev/", "proc/", "sys/")


def open_url(url, headers=None):
    request = urllib.request.Request(url, headers=headers or {})
    return urllib.request.urlopen(request, timeout=180)


def main():
    manifest_path, out_path = sys.argv[1], sys.argv[2]
    rootfs = json.load(open(manifest_path))["linuxSystem"]["rootfs"]
    image_digest = rootfs["imageManifest"]
    layer_digest = rootfs["layer"]

    token = json.load(open_url(
        "https://auth.docker.io/token?service=registry.docker.io&scope=repository:%s:pull" % REPO
    ))["token"]
    headers = {"Authorization": "Bearer " + token}
    base = "https://registry-1.docker.io/v2/%s" % REPO

    image = json.load(open_url(base + "/manifests/" + image_digest, headers))
    layers = [l for l in image["layers"] if l["digest"] == layer_digest]
    if not layers:
        raise SystemExit("the pinned layer %s is not in image %s" % (layer_digest, image_digest))

    with tarfile.open(out_path, "w") as out:
        for layer in layers:
            blob = open_url(base + "/blobs/" + layer["digest"], headers).read()
            got = "sha256:" + hashlib.sha256(blob).hexdigest()
            if got != layer["digest"]:
                raise SystemExit("layer hash mismatch: %s != %s" % (got, layer["digest"]))
            with tarfile.open(fileobj=io.BytesIO(blob), mode="r:*") as tar:
                for member in tar.getmembers():
                    name = member.name.lstrip("./")
                    if not name or name in ("dev", "proc", "sys") or name.startswith(SKIP_PREFIXES):
                        continue
                    if member.isfile():
                        out.addfile(member, tar.extractfile(member))
                    else:
                        out.addfile(member)
    print("wrote", out_path, "from", layer_digest)


if __name__ == "__main__":
    main()
