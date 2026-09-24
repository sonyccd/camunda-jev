import os
import unittest

from simulation.generator import Case, generate, load_corpus

CORPUS = os.path.join(os.path.dirname(__file__), "..", "corpus.json")


class GeneratorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = load_corpus(CORPUS)

    def test_generates_the_requested_count(self):
        self.assertEqual(len(generate(self.cases, 250, seed=1)), 250)

    def test_same_seed_is_reproducible(self):
        a = generate(self.cases, 60, seed=7)
        b = generate(self.cases, 60, seed=7)
        self.assertEqual(a, b)

    def test_different_seeds_differ(self):
        a = generate(self.cases, 60, seed=1)
        b = generate(self.cases, 60, seed=2)
        self.assertNotEqual(a, b)

    def test_case_ids_are_unique(self):
        ids = [c.case_id for c in generate(self.cases, 300, seed=3)]
        self.assertEqual(len(ids), len(set(ids)))

    def test_perturbation_never_changes_the_label(self):
        by_id = {c["id"]: c["expected"] for c in self.cases}
        for case in generate(self.cases, 400, seed=4):
            self.assertEqual(case.expected, by_id[case.corpus_id])

    def test_perturbation_actually_varies_the_text(self):
        # Draws of the same source case should not all be byte-identical,
        # or the run is 120 distinct strings wearing 2000 hats.
        generated = generate(self.cases, 600, seed=5)
        grouped = {}
        for case in generated:
            grouped.setdefault(case.corpus_id, set()).add(case.body)
        repeated = [bodies for bodies in grouped.values() if len(bodies) > 1]
        self.assertTrue(repeated, "no source case produced varied bodies")

    def test_subject_and_body_are_never_empty(self):
        for case in generate(self.cases, 120, seed=6):
            self.assertTrue(case.subject.strip())
            self.assertTrue(case.body.strip())

    def test_returns_case_instances(self):
        self.assertIsInstance(generate(self.cases, 5, seed=1)[0], Case)


if __name__ == "__main__":
    unittest.main()
