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
import socket
import sys
import tarfile
import time
import urllib.error
import urllib.parse
import urllib.request
from http.client import HTTPException

REPO = "library/debian"
SKIP_PREFIXES = ("dev/", "proc/", "sys/")
ATTEMPTS = 5
CHUNK = 8 << 20
TIMEOUT = 180
RETRY_STATUS = {400, 408, 425, 429, 500, 502, 503, 504}


class _NoAuthRedirect(urllib.request.HTTPRedirectHandler):
    """Keep the registry bearer token off the CDN host.

    Blob requests are answered with a redirect to a CDN. The token is scoped to
    the registry and some edges reject it outright when it is replayed there,
    which surfaces as an unhelpful 400.
    """

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new = super().redirect_request(req, fp, code, msg, headers, newurl)
        same_host = urllib.parse.urlsplit(newurl).netloc == urllib.parse.urlsplit(req.full_url).netloc
        if new is not None and not same_host:
            for key in [k for k in new.headers if k.lower() == "authorization"]:
                del new.headers[key]
        return new


_opener = urllib.request.build_opener(_NoAuthRedirect)


def _pause(attempt, error):
    retry_after = getattr(error, "headers", None) and error.headers.get("Retry-After")
    if retry_after and retry_after.isdigit():
        time.sleep(min(int(retry_after), 30))
    else:
        time.sleep(2 ** attempt)


def fetch(url, headers=None):
    """GET a URL, retrying what is worth retrying.

    Registry and CDN failures are transient far more often than not: one 400 from
    an edge node used to fail the whole build and leave `main` without an APK.
    The digest check in main() is what actually decides correctness, so being
    patient here costs nothing but a few seconds.
    """
    last = None
    for attempt in range(ATTEMPTS):
        try:
            request = urllib.request.Request(url, headers=headers or {})
            with _opener.open(request, timeout=TIMEOUT) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            last = error
            if error.code not in RETRY_STATUS:
                raise
            error.close()
        except (urllib.error.URLError, socket.timeout, HTTPException) as error:
            last = error
        if attempt + 1 < ATTEMPTS:
            print("retrying %s (attempt %d failed: %s)" % (url, attempt + 1, last), file=sys.stderr)
            _pause(attempt, last)
    raise SystemExit("giving up on %s after %d attempts: %s" % (url, ATTEMPTS, last))


def fetch_blob(url, headers, size):
    """Download a blob in ranges so a dropped connection resumes, not restarts.

    The pinned layer is ~28 MB over TLS; a single reset used to discard all of it.
    """
    blob = bytearray()
    while len(blob) < size:
        start = len(blob)
        end = min(start + CHUNK, size) - 1
        chunk = fetch(url, dict(headers, Range="bytes=%d-%d" % (start, end)))
        if not chunk:
            raise SystemExit("empty response at offset %d of %s" % (start, url))
        blob += chunk
    if len(blob) > size:
        raise SystemExit("blob is %d bytes, pinned size is %d" % (len(blob), size))
    return bytes(blob)


def main():
    manifest_path, out_path = sys.argv[1], sys.argv[2]
    rootfs = json.load(open(manifest_path))["linuxSystem"]["rootfs"]
    image_digest = rootfs["imageManifest"]
    layer_digest = rootfs["layer"]

    token = json.loads(fetch(
        "https://auth.docker.io/token?service=registry.docker.io&scope=repository:%s:pull" % REPO
    ))["token"]
    headers = {"Authorization": "Bearer " + token}
    base = "https://registry-1.docker.io/v2/%s" % REPO

    image = json.loads(fetch(base + "/manifests/" + image_digest, headers))
    layers = [l for l in image["layers"] if l["digest"] == layer_digest]
    if not layers:
        raise SystemExit("the pinned layer %s is not in image %s" % (layer_digest, image_digest))

    with tarfile.open(out_path, "w") as out:
        for layer in layers:
            blob = fetch_blob(base + "/blobs/" + layer["digest"], headers, layer["size"])
            got = "sha256:" + hashlib.sha256(blob).hexdigest()
            if got != layer["digest"]:
                raise SystemExit("layer hash mismatch: %s != %s" % (got, layer["digest"]))
            with tarfile.open(fileobj=io.BytesIO(blob), mode="r:*") as tar:
                for member in tar.getmembers():
                    name = member.name
                    while name.startswith("./"):
                        name = name[2:]
                    # Finding 29: lstrip("./") strips characters, not a prefix, and
                    # a layer is untrusted input even when its digest is pinned.
                    # Refuse anything that would escape the root on unpack instead
                    # of copying the name through.
                    if name.startswith("/") or ".." in name.split("/"):
                        raise SystemExit("layer member escapes the rootfs: %r" % member.name)
                    if not name or name in ("dev", "proc", "sys") or name.startswith(SKIP_PREFIXES):
                        continue
                    if member.isfile():
                        out.addfile(member, tar.extractfile(member))
                    else:
                        out.addfile(member)
    print("wrote", out_path, "from", layer_digest)


if __name__ == "__main__":
    main()
