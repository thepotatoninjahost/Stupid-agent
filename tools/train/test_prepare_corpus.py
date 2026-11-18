#!/usr/bin/env python3
"""Tests for prepare_corpus: the corpus builder must refuse bad input loudly."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import prepare_corpus as pc


def write_ledger(tmp: Path, rows: list[dict]) -> Path:
    path = tmp / "ledger.jsonl"
    path.write_text("\n".join(json.dumps(r) for r in rows) + "\n", encoding="utf-8")
    return path


def chat(i: int) -> dict:
    return {"kind": "CHAT_ASSISTANT", "input": f"q{i}", "output": f"a{i}", "weight": 0.6}


class CorpusTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())

    def test_splits_newest_tenth_out_of_training(self) -> None:
        source = write_ledger(self.tmp, [chat(i) for i in range(20)])
        manifest = pc.build(source, self.tmp / "out")
        self.assertEqual(20, manifest["rowsAnswerable"])
        self.assertEqual(18, manifest["train"])
        self.assertEqual(2, manifest["val"])
        val_lines = (self.tmp / "out" / "val.jsonl").read_text().strip().splitlines()
        self.assertEqual(2, len(val_lines))
        self.assertEqual("q19", json.loads(val_lines[-1])["input"])

    def test_a_tiny_corpus_has_no_validation_slice(self) -> None:
        source = write_ledger(self.tmp, [chat(i) for i in range(3)])
        manifest = pc.build(source, self.tmp / "out")
        self.assertEqual(3, manifest["train"])
        self.assertEqual(0, manifest["val"])

    def test_tool_and_research_rows_are_never_training_targets(self) -> None:
        source = write_ledger(
            self.tmp,
            [
                {"kind": "TOOL_CALL", "input": "ls", "output": "a.kt", "weight": 1.0},
                {"kind": "RESEARCH_NOTE", "input": "what is qlora", "output": "text", "weight": 0.5},
                chat(1),
            ],
        )
        manifest = pc.build(source, self.tmp / "out")
        self.assertEqual(1, manifest["rowsAnswerable"])
        body = (self.tmp / "out" / "train.jsonl").read_text()
        self.assertNotIn("TOOL_CALL", body)
        self.assertNotIn("RESEARCH_NOTE", body)

    def test_an_empty_output_is_not_training_material(self) -> None:
        source = write_ledger(
            self.tmp,
            [{"kind": "CHAT_ASSISTANT", "input": "q", "output": "   ", "weight": 0.6}, chat(1)],
        )
        manifest = pc.build(source, self.tmp / "out")
        self.assertEqual(1, manifest["rowsAnswerable"])

    def test_a_repeated_fact_is_trained_once_at_its_best_weight(self) -> None:
        rows = [chat(1), chat(1)] + [chat(i) for i in range(2, 11)]
        rows[1]["weight"] = 1.9
        source = write_ledger(self.tmp, rows)
        manifest = pc.build(source, self.tmp / "out")
        self.assertEqual(10, manifest["rowsAnswerable"])

    def test_bad_json_stops_the_run_and_names_the_line(self) -> None:
        path = self.tmp / "ledger.jsonl"
        path.write_text('{"kind":"CHAT_ASSISTANT","input":"q","output":"a"}\nnot json\n', encoding="utf-8")
        with self.assertRaises(pc.CorpusError) as caught:
            pc.build(path, self.tmp / "out")
        self.assertIn("line 2", str(caught.exception))

    def test_missing_field_stops_the_run(self) -> None:
        path = self.tmp / "ledger.jsonl"
        path.write_text('{"kind":"CHAT_ASSISTANT","input":"q"}\n', encoding="utf-8")
        with self.assertRaises(pc.CorpusError) as caught:
            pc.build(path, self.tmp / "out")
        self.assertIn("output", str(caught.exception))

    def test_wrong_types_are_refused(self) -> None:
        path = self.tmp / "ledger.jsonl"
        path.write_text('{"kind":"CHAT_ASSISTANT","input":42,"output":"a"}\n', encoding="utf-8")
        with self.assertRaises(pc.CorpusError):
            pc.build(path, self.tmp / "out")

    def test_negative_weight_is_refused(self) -> None:
        path = self.tmp / "ledger.jsonl"
        path.write_text('{"kind":"CHAT_ASSISTANT","input":"q","output":"a","weight":-1}\n', encoding="utf-8")
        with self.assertRaises(pc.CorpusError) as caught:
            pc.build(path, self.tmp / "out")
        self.assertIn("greater than zero", str(caught.exception))

    def test_empty_export_is_an_error_not_an_empty_corpus(self) -> None:
        path = self.tmp / "ledger.jsonl"
        path.write_text("\n\n", encoding="utf-8")
        with self.assertRaises(pc.CorpusError):
            pc.build(path, self.tmp / "out")

    def test_the_same_input_gives_the_same_manifest_hash(self) -> None:
        source = write_ledger(self.tmp, [chat(i) for i in range(12)])
        first = pc.build(source, self.tmp / "a")
        second = pc.build(source, self.tmp / "b")
        self.assertEqual(first["trainSha256"], second["trainSha256"])
        self.assertEqual(first["valSha256"], second["valSha256"])

    def test_command_line_exit_code_says_what_happened(self) -> None:
        good = write_ledger(self.tmp, [chat(i) for i in range(4)])
        self.assertEqual(0, pc.main([str(good), "--out", str(self.tmp / "ok")]))
        bad = self.tmp / "bad.jsonl"
        bad.write_text("{oops\n", encoding="utf-8")
        self.assertEqual(2, pc.main([str(bad), "--out", str(self.tmp / "no")]))


if __name__ == "__main__":
    unittest.main()
