"""Tests for the GPU trainer's decisions — the parts that must be right before
any GPU is rented. The torch path is not exercised here (no GPU on this host);
what is exercised is every refusal and the "must beat its base" rule.
"""
import json
import tempfile
import unittest
from pathlib import Path

import train_vorv_l as t


class CorpusReading(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())

    def write(self, name, text):
        path = self.dir / name
        path.write_text(text, encoding="utf-8")
        return path

    def test_reads_valid_rows(self):
        p = self.write("train.jsonl", '{"kind":"CHAT_ASSISTANT","input":"q","output":"a","weight":0.6}\n')
        rows = t.read_jsonl(p)
        self.assertEqual(1, len(rows))
        self.assertEqual("q", rows[0]["input"])

    def test_missing_file_is_a_sentence(self):
        with self.assertRaises(t.TrainError) as ctx:
            t.read_jsonl(self.dir / "nope.jsonl")
        self.assertIn("missing", str(ctx.exception))

    def test_bad_json_names_the_line(self):
        p = self.write("train.jsonl", '{"input":"q","output":"a"}\n{oops\n')
        with self.assertRaises(t.TrainError) as ctx:
            t.read_jsonl(p)
        self.assertIn("line 2", str(ctx.exception))

    def test_empty_field_is_refused(self):
        p = self.write("train.jsonl", '{"input":"q","output":"   "}\n')
        with self.assertRaises(t.TrainError) as ctx:
            t.read_jsonl(p)
        self.assertIn("'output' is empty", str(ctx.exception))

    def test_empty_file_is_refused(self):
        p = self.write("train.jsonl", "\n\n")
        with self.assertRaises(t.TrainError):
            t.read_jsonl(p)


class WeightsAndPrompts(unittest.TestCase):
    def test_weight_defaults_and_rejects_nonsense(self):
        self.assertEqual(1.0, t.example_weight({"input": "q", "output": "a"}))
        self.assertEqual(2.0, t.example_weight({"weight": 2.0}))
        for bad in (0, -1, "high", True, None):
            with self.assertRaises(t.TrainError):
                t.example_weight({"weight": bad})

    def test_corrections_are_phrased_as_the_alternative(self):
        text = t.to_prompt({"kind": "CORRECTION", "input": "used a shell loop", "output": "use the SDK"})
        self.assertIn("Instead of: used a shell loop", text)
        self.assertIn("Do this: use the SDK", text)

    def test_plain_answers_are_request_answer(self):
        text = t.to_prompt({"kind": "CHAT_ASSISTANT", "input": "q", "output": "a"})
        self.assertTrue(text.startswith("### Request\nq\n### Answer\na"))


class TheOneRule(unittest.TestCase):
    def test_only_a_lower_loss_counts(self):
        self.assertTrue(t.is_improvement(1.20, 1.10))
        self.assertFalse(t.is_improvement(1.20, 1.20))
        self.assertFalse(t.is_improvement(1.20, 1.21))


class EndToEndRefusals(unittest.TestCase):
    """main() must refuse, with exit codes and without writing an adapter."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.corpus = self.dir / "corpus"
        self.corpus.mkdir()
        self.out = self.dir / "run"

    def run_main(self, train_rows, val_rows):
        (self.corpus / "train.jsonl").write_text("\n".join(json.dumps(r) for r in train_rows) + "\n", encoding="utf-8")
        (self.corpus / "val.jsonl").write_text("\n".join(json.dumps(r) for r in val_rows) + "\n", encoding="utf-8")
        return t.main(["--corpus", str(self.corpus), "--out", str(self.out)])

    def test_empty_validation_slice_stops_before_any_gpu_work(self):
        code = self.run_main([{"input": "q", "output": "a"}], [])
        self.assertEqual(2, code)
        self.assertFalse((self.out / "adapter").exists())

    def test_no_validation_file_stops_with_a_reason(self):
        (self.corpus / "train.jsonl").write_text('{"input":"q","output":"a"}\n', encoding="utf-8")
        code = t.main(["--corpus", str(self.corpus), "--out", str(self.out)])
        self.assertEqual(2, code)
        self.assertFalse((self.out / "metrics.json").exists())

    def test_without_the_ml_stack_it_says_which_pieces_are_missing(self):
        try:
            import torch  # noqa: F401
        except ImportError:
            code = self.run_main([{"input": "q", "output": "a"}], [{"input": "q2", "output": "a2"}])
            self.assertEqual(3, code)
        else:
            self.skipTest("torch is installed here")


if __name__ == "__main__":
    unittest.main()
