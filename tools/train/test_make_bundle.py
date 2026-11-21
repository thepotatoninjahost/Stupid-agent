#!/usr/bin/env python3
"""Tests for make_bundle.py: the GPU side must refuse to pack a weak bundle."""

from __future__ import annotations

import json
import os
import tempfile
import unittest
import zipfile

import make_bundle


def probes(passed=None, total=None, suite=make_bundle.SUITE_VERSION):
    counts = {kind: 4 for kind in make_bundle.KINDS}
    return {
        "suiteVersion": suite,
        "passed": passed if passed is not None else dict(counts),
        "total": total if total is not None else dict(counts),
    }


class MakeBundleTest(unittest.TestCase):
    def setUp(self):
        # The 1 GiB floor is a real rail for real models; tests use a toy file and
        # lower the floor so the other checks are what is being measured.
        self._floor = make_bundle.MIN_MODEL_BYTES
        make_bundle.MIN_MODEL_BYTES = 1024
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name
        self.model = os.path.join(self.dir, "model.gguf")
        with open(self.model, "wb") as handle:
            handle.write(b"gguf" * 4096)
        self.metrics = os.path.join(self.dir, "metrics.json")
        with open(self.metrics, "w", encoding="utf-8") as handle:
            json.dump({"baseLoss": 1.31, "tunedLoss": 1.08, "stepped": 120}, handle)
        self.probes = os.path.join(self.dir, "probes.json")
        with open(self.probes, "w", encoding="utf-8") as handle:
            json.dump(probes(), handle)

    def tearDown(self):
        make_bundle.MIN_MODEL_BYTES = self._floor
        self.tmp.cleanup()

    def build(self, **kwargs):
        args = {
            "model": self.model,
            "probes": probes(),
            "metrics": json.load(open(self.metrics)),
            "base": "Tesslate/OmniCoder-9B-GGUF",
            "corpus_sha256": "abc123",
            "config": '{"loraR": 16}',
        }
        args.update(kwargs)
        return make_bundle.build_manifest(**args)

    def test_a_complete_run_gives_a_manifest_with_the_file_facts(self):
        manifest = self.build()
        self.assertEqual("gpu", manifest["origin"])
        self.assertEqual(os.path.getsize(self.model), manifest["model"]["bytes"])
        self.assertEqual(64, len(manifest["model"]["sha256"]))
        self.assertEqual(1.08, manifest["valLoss"])
        self.assertEqual(make_bundle.SUITE_VERSION, manifest["suiteVersion"])
        self.assertEqual(set(make_bundle.KINDS), set(manifest["passed"]))

    def test_the_zip_holds_manifest_metrics_and_model(self):
        manifest = self.build()
        out = os.path.join(self.dir, "bundle.zip")
        make_bundle.write_bundle(self.model, manifest, self.metrics, out)
        with zipfile.ZipFile(out) as archive:
            names = set(archive.namelist())
            self.assertEqual({"manifest.json", "metrics.json", "model.gguf"}, names)
            self.assertEqual(
                os.path.getsize(self.model), archive.getinfo("model.gguf").file_size
            )
            stored = json.loads(archive.read("manifest.json").decode())
            self.assertEqual(manifest["model"]["sha256"], stored["model"]["sha256"])

    def test_a_stub_model_is_refused(self):
        make_bundle.MIN_MODEL_BYTES = 10 * 1024 * 1024
        with self.assertRaises(make_bundle.BundleError) as caught:
            self.build()
        self.assertIn("Refusing to pack a stub", str(caught.exception))

    def test_a_suite_mismatch_is_refused(self):
        with self.assertRaises(make_bundle.BundleError) as caught:
            self.build(probes=probes(suite=2))
        self.assertIn("cannot be compared", str(caught.exception))

    def test_a_missing_probe_kind_is_named(self):
        partial = {kind: 4 for kind in make_bundle.KINDS if kind != "PROJECT"}
        with self.assertRaises(make_bundle.BundleError) as caught:
            self.build(probes=probes(passed=partial, total=partial))
        self.assertIn("PROJECT", str(caught.exception))

    def test_no_loss_means_no_bundle(self):
        with self.assertRaises(make_bundle.BundleError) as caught:
            self.build(metrics={"baseLoss": 1.3, "stepped": 10})
        self.assertIn("valLoss", str(caught.exception))

    def test_no_corpus_fingerprint_means_no_bundle(self):
        with self.assertRaises(make_bundle.BundleError) as caught:
            self.build(corpus_sha256="")
        self.assertIn("corpus-sha256", str(caught.exception))

    def test_more_passes_than_probes_is_refused(self):
        impossible = {kind: 9 for kind in make_bundle.KINDS}
        with self.assertRaises(make_bundle.BundleError) as caught:
            self.build(probes=probes(passed=impossible))
        self.assertIn("more passes than probes", str(caught.exception))

    def test_main_exits_nonzero_and_writes_nothing_on_a_bad_run(self):
        out = os.path.join(self.dir, "should-not-exist.zip")
        code = make_bundle.main([
            "--model", self.model, "--probes", self.probes, "--metrics", self.metrics,
            "--base", "", "--corpus-sha256", "x", "--out", out,
        ])
        self.assertEqual(2, code)
        self.assertFalse(os.path.exists(out))

    def test_broken_probe_json_is_refused(self):
        with open(self.probes, "w", encoding="utf-8") as handle:
            handle.write("{nope")
        with self.assertRaises(make_bundle.BundleError) as caught:
            make_bundle.read_json(self.probes)
        self.assertIn("not valid JSON", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
