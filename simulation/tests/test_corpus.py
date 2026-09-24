import json
import os
import unittest

CORPUS = os.path.join(os.path.dirname(__file__), "..", "corpus.json")
TEAMS = {"billing", "technical", "sales"}
LABELS = TEAMS | {"ambiguous"}


class CorpusContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(CORPUS, encoding="utf-8") as handle:
            cls.doc = json.load(handle)
        cls.cases = cls.doc["cases"]

    def test_version_is_declared(self):
        self.assertEqual(self.doc["version"], 1)

    def test_every_label_is_known(self):
        for case in self.cases:
            self.assertIn(case["expected"], LABELS, case["id"])

    def test_ids_are_unique(self):
        ids = [c["id"] for c in self.cases]
        self.assertEqual(len(ids), len(set(ids)))

    def test_no_empty_fields(self):
        for case in self.cases:
            for field in ("id", "expected", "subject", "body"):
                self.assertTrue(str(case.get(field, "")).strip(), f"{case['id']}.{field}")

    def test_corpus_is_large_enough(self):
        self.assertGreaterEqual(len(self.cases), 110)

    def test_each_team_is_well_represented(self):
        for team in TEAMS:
            count = sum(1 for c in self.cases if c["expected"] == team)
            self.assertGreaterEqual(count, 25, f"{team} has only {count}")

    def test_ambiguous_slice_is_within_band(self):
        # Enough to exercise escalation, not so many the run is mostly noise.
        count = sum(1 for c in self.cases if c["expected"] == "ambiguous")
        share = count / len(self.cases)
        self.assertTrue(0.10 <= share <= 0.22, f"ambiguous share {share:.2f}")

    def test_bodies_vary_in_length(self):
        # Uniform length is a symptom of templated text, which would flatter Jev.
        lengths = sorted(len(c["body"]) for c in self.cases)
        self.assertLess(lengths[len(lengths) // 10], 120)
        self.assertGreater(lengths[-len(lengths) // 10], 400)


if __name__ == "__main__":
    unittest.main()
