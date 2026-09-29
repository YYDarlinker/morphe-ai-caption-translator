"""Offline scoreboard regression checks; never call the network."""
from copy import deepcopy
import json
import pathlib
import unittest
from unittest import mock

import run


class FrozenReplayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.evidence = run.read_evidence()
        cls.result = run.score(cls.evidence)

    def test_frozen_cost_and_visibility(self):
        result = self.result
        self.assertEqual(result["case_totals"], {"通过": 0, "失败": 10, "未验证": 2})
        self.assertEqual({k:v["total"] for k,v in result["metrics"]["invisible_ms"].items()},
                         {"pending_translation": 3103, "event_review": 12550, "overflow": 7332})
        self.assertEqual(result["frozen_request_attempts"], 11)
        self.assertEqual(result["frozen_accepted_blocks"], 10)
        self.assertEqual(result["frozen_token_audit"], {"total_tokens": 27920, "input_tokens": 18599, "output_tokens": 9321})

    def test_ownership_and_local_cases(self):
        self.assertTrue(self.result["metrics"]["source_ownership_all_cases"])
        self.assertEqual(self.result["metrics"]["fragment_hits"], {"A04": 1, "A08": 1, "A11": 1})
        self.assertEqual(self.result["metrics"]["short_pages_a12_only_under_1000ms"], 1)
        self.assertEqual(self.result["metrics"]["font_shrink_events"], 1)
        self.assertEqual(self.result["metrics"]["overflow_events"], 1)
        self.assertEqual(self.result["metrics"]["bad_translation_string_hits"],
                         {"A02": 1, "A03": 1, "A09": 1})
        for case in ("A11", "A12"):
            self.assertEqual(self.result["cases"][case]["status"], "未验证")
            self.assertTrue(self.result["cases"][case]["generated_layer_alarm"])
            self.assertEqual(self.result["cases"][case]["display_evidence"], "仅生成")
        self.assertFalse(self.result["cases"]["A07"]["checks"]["caption_presented"])
        self.assertFalse(self.result["cases"]["A10"]["checks"]["caption_presented"])
        self.assertEqual(self.result["cases"]["A01"]["checks"]["reading_time_after_first_caption_ms"], 3845)

    def test_generated_live_evidence_is_not_presented(self):
        blocks = [{"block": n, "response": r["plan"]} for n, r in self.evidence["by_block"].items()]
        generated = run.generated_case_results(self.evidence, blocks)
        self.assertEqual(set(generated), set(run.CASES))
        self.assertTrue(all(row["display_status"] == "未验证" for row in generated.values()))
        self.assertTrue(all(row["source_ownership_complete_once"] for row in generated.values()))
        self.assertEqual(generated["A12"]["generated_layer_alarms"]["638-641独立短页"], 1)
    def test_dropped_source_word_is_not_accepted_as_covered(self):
        evidence = deepcopy(self.evidence)
        event = next(e for e in evidence["events"] if e["from"] == 25)
        event["from"] += 1
        result = run.score(evidence)
        self.assertIn(25, result["cases"]["A02"]["source_invariant"]["missing"])
        self.assertFalse(result["metrics"]["source_ownership_all_cases"])

    def test_srt_is_required(self):
        with mock.patch.object(run, "srt_words", return_value=["tampered"]):
            with self.assertRaisesRegex(ValueError, "SRT prefix"):
                run.read_evidence()

    def test_default_never_touches_network_or_live_env(self):
        with mock.patch.object(run.urllib.request, "build_opener", side_effect=AssertionError("network")), \
             mock.patch.object(run, "live_once", side_effect=AssertionError("live")):
            self.assertEqual(run.main([]), 0)
        expected = pathlib.Path(run.RESULT).read_bytes()
        with mock.patch.object(run.urllib.request, "build_opener", side_effect=AssertionError("network")):
            self.assertEqual(run.main([]), 0)
        self.assertEqual(expected, pathlib.Path(run.RESULT).read_bytes())

    def test_live_requires_explicit_credentials(self):
        with mock.patch.dict(run.os.environ, {"MORPHE_P4_API_KEY": ""}):
            with self.assertRaisesRegex(ValueError, "needs MORPHE_P4_API_KEY"):
                run.live_once(self.evidence)
        with self.assertRaisesRegex(ValueError, "Invalid OpenAI-compatible"):
            run.api_endpoint("file:///tmp/no-download")
        self.assertIn("Target language: zh-Hans", run.current_prompt())


if __name__ == "__main__":
    unittest.main()
