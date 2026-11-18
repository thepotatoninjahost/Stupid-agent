#!/usr/bin/env python3
"""Tests for the speed gate. Run: python3 tools/bench/test_check_speed.py"""
from __future__ import annotations

import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import check_speed


class ParseTest(unittest.TestCase):
    def test_header_and_rows(self) -> None:
        rows = check_speed.parse("generation\tconfig\ttokens_per_second\tat\n1\tvorv-s\t12.5\t1000\n")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["tokens_per_second"], 12.5)

    def test_a_short_line_is_refused_with_its_number(self) -> None:
        with self.assertRaises(ValueError) as caught:
            check_speed.parse("1\tvorv-s\t12.5\n")
        self.assertIn("line 1", str(caught.exception))

    def test_a_non_number_is_refused(self) -> None:
        with self.assertRaises(ValueError):
            check_speed.parse("1\tvorv-s\tfast\t1000\n")


class CheckTest(unittest.TestCase):
    def rows(self, *speeds: tuple[int, float]) -> list[dict]:
        return [
            {"generation": gen, "config": "vorv-s", "tokens_per_second": tps, "at": gen * 1000}
            for gen, tps in speeds
        ]

    def test_a_single_build_has_nothing_to_compare(self) -> None:
        lines, regressions = check_speed.check(self.rows((1, 10.0)))
        self.assertEqual(regressions, [])
        self.assertIn("first record", lines[0])

    def test_a_drop_inside_the_allowance_passes(self) -> None:
        _, regressions = check_speed.check(self.rows((1, 10.0), (2, 8.5)))
        self.assertEqual(regressions, [])

    def test_exactly_twenty_percent_down_still_passes(self) -> None:
        _, regressions = check_speed.check(self.rows((1, 10.0), (2, 8.0)))
        self.assertEqual(regressions, [])

    def test_a_drop_above_twenty_percent_is_a_regression(self) -> None:
        _, regressions = check_speed.check(self.rows((1, 10.0), (2, 7.9)))
        self.assertEqual(len(regressions), 1)
        self.assertIn("21% below", regressions[0])

    def test_architectures_are_compared_with_themselves(self) -> None:
        rows = [
            {"generation": 1, "config": "vorv-s", "tokens_per_second": 10.0, "at": 1000},
            {"generation": 2, "config": "vorv-m", "tokens_per_second": 3.0, "at": 2000},
        ]
        _, regressions = check_speed.check(rows)
        self.assertEqual(regressions, [])


class MainTest(unittest.TestCase):
    def run_main(self, path: str) -> tuple[int, str]:
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = check_speed.main(["check_speed.py", path])
        return code, buffer.getvalue()

    def test_an_empty_record_file_claims_nothing_and_passes(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "records.tsv"
            path.write_text("generation\tconfig\ttokens_per_second\tat\n", encoding="utf-8")
            code, out = self.run_main(str(path))
        self.assertEqual(code, 0)
        self.assertIn("no speed has been measured", out)

    def test_a_missing_file_passes_and_says_so(self) -> None:
        code, out = self.run_main("/tmp/there-is-no-such-speed-file.tsv")
        self.assertEqual(code, 0)
        self.assertIn("does not exist", out)

    def test_a_regression_exits_one(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "records.tsv"
            path.write_text(
                "generation\tconfig\ttokens_per_second\tat\n"
                "1\tvorv-s\t10.0\t1000\n"
                "2\tvorv-s\t7.0\t2000\n",
                encoding="utf-8",
            )
            code, out = self.run_main(str(path))
        self.assertEqual(code, 1)
        self.assertIn("REGRESSION", out)


if __name__ == "__main__":
    unittest.main()
