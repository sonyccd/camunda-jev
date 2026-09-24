# simulation/tests/test_report.py
import os
import tempfile
import unittest

from simulation.report import (
    Observation,
    classify,
    classify_at,
    default_thresholds,
    format_report,
    summarize,
    sweep,
    write_csv,
)


def obs(expected, decided, jev_choice, confidence, case_id="c", sent=0.0, seen=0.5):
    return Observation(
        case_id=case_id,
        corpus_id=case_id,
        expected=expected,
        subject="s",
        sent_at=sent,
        observed_at=seen,
        choice=(jev_choice if decided else "undecided"),
        decided=decided,
        jev_choice=jev_choice,
        confidence=confidence,
        probabilities={"billing": 0.1, "technical": 0.8, "sales": 0.1},
    )


class ClassifyTest(unittest.TestCase):
    """One assertion per row of the taxonomy table in the spec."""

    def test_committed_and_right_is_a_correct_route(self):
        self.assertEqual(classify("billing", True, "billing"), "correct_route")

    def test_committed_and_wrong_is_a_misroute(self):
        self.assertEqual(classify("billing", True, "technical"), "misroute")

    def test_committed_on_ambiguous_is_overconfident(self):
        self.assertEqual(classify("ambiguous", True, "technical"), "overconfident")

    def test_escalating_ambiguous_is_warranted(self):
        self.assertEqual(classify("ambiguous", False, "technical"), "warranted_escalation")

    def test_escalating_when_the_pick_was_right_is_wasted(self):
        self.assertEqual(classify("billing", False, "billing"), "wasted_escalation")

    def test_escalating_when_the_pick_was_wrong_is_saved(self):
        self.assertEqual(classify("billing", False, "technical"), "saved_escalation")


class ClassifyAtTest(unittest.TestCase):
    def test_threshold_boundary_is_inclusive(self):
        self.assertEqual(classify_at("billing", 0.70, "billing", 0.70), "correct_route")

    def test_below_threshold_escalates(self):
        self.assertEqual(classify_at("billing", 0.69, "billing", 0.70), "wasted_escalation")


class SummarizeTest(unittest.TestCase):
    def setUp(self):
        self.observations = [
            obs("billing", True, "billing", 0.9),        # correct_route
            obs("technical", True, "sales", 0.8),        # misroute
            obs("ambiguous", True, "sales", 0.85),       # overconfident
            obs("ambiguous", False, "sales", 0.4),       # warranted_escalation
            obs("sales", False, "sales", 0.5),           # wasted_escalation
            obs("sales", False, "billing", 0.45),        # saved_escalation
        ]

    def test_counts_every_outcome(self):
        summary = summarize(self.observations, requested_rate=10.0, elapsed=6.0)
        for outcome in ("correct_route", "misroute", "overconfident",
                        "warranted_escalation", "wasted_escalation", "saved_escalation"):
            self.assertEqual(summary["outcomes"][outcome], 1, outcome)

    def test_rates_are_fractions_of_resolved(self):
        summary = summarize(self.observations, requested_rate=10.0, elapsed=6.0)
        self.assertEqual(summary["resolved"], 6)
        self.assertAlmostEqual(summary["escalation_rate"], 3 / 6)
        self.assertAlmostEqual(summary["misroute_rate"], 1 / 6)

    def test_unresolved_are_counted_not_dropped(self):
        pending = Observation("x", "x", "billing", "s", 0.0, None, None, None, None, None, {})
        summary = summarize(self.observations + [pending], requested_rate=10.0, elapsed=6.0)
        self.assertEqual(summary["unresolved"], 1)
        self.assertEqual(summary["resolved"], 6)

    def test_achieved_throughput_uses_elapsed_time(self):
        summary = summarize(self.observations, requested_rate=10.0, elapsed=3.0)
        self.assertAlmostEqual(summary["achieved_rate"], 6 / 3.0)
        self.assertEqual(summary["requested_rate"], 10.0)

    def test_latency_percentiles_are_reported(self):
        summary = summarize(self.observations, requested_rate=10.0, elapsed=6.0)
        self.assertAlmostEqual(summary["latency_ms"]["p50"], 500.0)

    def test_confusion_matrix_counts_expected_against_pick(self):
        summary = summarize(self.observations, requested_rate=10.0, elapsed=6.0)
        self.assertEqual(summary["confusion"][("technical", "sales")], 1)

    def test_empty_input_does_not_divide_by_zero(self):
        summary = summarize([], requested_rate=1.0, elapsed=1.0)
        self.assertEqual(summary["resolved"], 0)
        self.assertEqual(summary["escalation_rate"], 0.0)


class SweepTest(unittest.TestCase):
    def test_sweep_recomputes_outcomes_at_each_threshold(self):
        # Confidence 0.6, pick correct. Below t=0.7 it escalates (wasted);
        # at or below 0.6 it commits and is correct.
        rows = sweep([obs("billing", True, "billing", 0.6)], [0.5, 0.7])
        by_t = {row["threshold"]: row for row in rows}
        self.assertEqual(by_t[0.5]["outcomes"]["correct_route"], 1)
        self.assertEqual(by_t[0.7]["outcomes"]["wasted_escalation"], 1)

    def test_sweep_reports_rates_and_total_error(self):
        observations = [
            obs("billing", True, "technical", 0.9),  # misroute at low t
            obs("sales", True, "sales", 0.6),
        ]
        rows = sweep(observations, [0.5])
        row = rows[0]
        self.assertAlmostEqual(row["misroute_rate"], 0.5)
        self.assertAlmostEqual(row["escalation_rate"], 0.0)
        # total_error excludes overconfident by design.
        self.assertEqual(row["total_error"], 1)

    def test_total_error_excludes_overconfident(self):
        rows = sweep([obs("ambiguous", True, "sales", 0.99)], [0.5])
        self.assertEqual(rows[0]["outcomes"]["overconfident"], 1)
        self.assertEqual(rows[0]["total_error"], 0)

    def test_default_thresholds_span_the_documented_range(self):
        thresholds = default_thresholds()
        self.assertEqual(thresholds[0], 0.5)
        self.assertEqual(thresholds[-1], 0.95)
        self.assertEqual(len(thresholds), 10)

    def test_sweep_ignores_unresolved(self):
        pending = Observation("x", "x", "billing", "s", 0.0, None, None, None, None, None, {})
        rows = sweep([obs("billing", True, "billing", 0.9), pending], [0.5])
        self.assertEqual(sum(rows[0]["outcomes"].values()), 1)


class OutputTest(unittest.TestCase):
    def test_csv_has_a_row_per_case_and_a_header(self):
        observations = [obs("billing", True, "billing", 0.9, case_id="a"),
                        obs("sales", False, "billing", 0.4, case_id="b")]
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "out.csv")
            write_csv(observations, path)
            with open(path, encoding="utf-8") as handle:
                lines = handle.read().strip().splitlines()
        self.assertEqual(len(lines), 3)
        self.assertIn("outcome", lines[0])
        self.assertIn("jevChoice", lines[0])

    def test_report_mentions_the_poll_interval_as_the_error_bound(self):
        summary = summarize([obs("billing", True, "billing", 0.9)], 10.0, 1.0)
        text = format_report(summary, sweep([obs("billing", True, "billing", 0.9)], [0.5]), 2.0)
        self.assertIn("2.0s", text)
        self.assertIn("escalation", text.lower())


if __name__ == "__main__":
    unittest.main()
