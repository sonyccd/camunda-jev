# Load Simulation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Drive thousands of labelled synthetic support tickets through a real Camunda cluster at a controlled rate, then report throughput, escalation rate, routing correctness, and what every other confidence threshold would have produced.

**Architecture:** Two pure modules (`generator.py`, `report.py`) that are data-in/data-out and fully unit tested, plus one I/O module (`loadtest.py`) that owns all network and timing. A separate BPMN process whose confident branches end immediately and whose `undecided` branch parks at a user task, so throughput and escalations are both directly countable.

**Tech Stack:** Python 3.9+ standard library only (`json`, `csv`, `random`, `urllib.request`, `threading`, `unittest`). Camunda 8.9 REST API v2. No pip installs anywhere, including CI.

**Spec:** `docs/superpowers/specs/2026-09-24-load-simulation-design.md`

## Global Constraints

- **Python 3.9+, standard library only.** No third-party packages. Tests use `unittest`, not pytest.
- **Do not modify** `src/main/java/**` or `examples/support-ticket-routing.bpmn`. The connector's behaviour is out of scope.
- Teams are exactly `billing`, `technical`, `sales`. The fourth label is `ambiguous`. The connector's escalation sentinel is `undecided`.
- Router job type is `ai.typesafe:jev-router:1`; element template id `ai.typesafe.camunda.jev.router.v1`.
- Camunda REST base is `http://localhost:8080`; process instances are created at `POST /v2/process-instances`, variables read at `POST /v2/variables/search`.
- The load-test process defines **no default threshold**. The feeder always supplies it.
- `expected` must never reach Jev. Only `ticket` is mapped to the connector's `state` input.
- Every task ends with a commit.

---

## File Structure

| File | Responsibility |
|---|---|
| `simulation/corpus.json` | Labelled seed emails. Data only. |
| `simulation/generator.py` | Sample + perturb the corpus to N cases. Pure. |
| `simulation/report.py` | Outcome taxonomy, summary, threshold sweep, CSV. Pure. |
| `simulation/loadtest.py` | Rate-limited feeder, batch-polling collector, CLI. All I/O. |
| `simulation/tests/test_corpus.py` | Corpus contract. |
| `simulation/tests/test_generator.py` | Determinism and label preservation. |
| `simulation/tests/test_report.py` | Taxonomy and sweep maths. |
| `simulation/tests/test_loadtest.py` | Rate limiter and request/response shapes, via injected fakes. |
| `examples/support-ticket-loadtest.bpmn` | The process under load. |
| `src/test/java/ai/typesafe/camunda/jev/ArtifactsTest.java` | BPMN contract incl. the no-leak assertion. |
| `jev` | `./jev simulate` subcommand. |
| `.github/workflows/ci.yml` | Run the Python tests. |

---

## Task 1: Labelled corpus

**Files:**
- Create: `simulation/corpus.json`
- Create: `simulation/tests/__init__.py` (empty)
- Test: `simulation/tests/test_corpus.py`

**Interfaces:**
- Consumes: nothing.
- Produces: `corpus.json` with shape `{"version": 1, "cases": [{"id": str, "expected": str, "subject": str, "body": str}]}`. `expected` ∈ `{"billing","technical","sales","ambiguous"}`. Every later task reads this shape.

**Note on the data:** the 120 emails are authored by hand during this task, not transcribed from this plan. The test below is the contract they must satisfy — write the test first, then author until it passes. Six representative cases are given to fix tone and shape; the rest follow the same pattern.

- [ ] **Step 1: Write the failing test**

```python
# simulation/tests/test_corpus.py
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest simulation.tests.test_corpus -v`
Expected: FAIL with `FileNotFoundError` — `corpus.json` does not exist.

- [ ] **Step 3: Author the corpus**

Create `simulation/corpus.json`. Target ~120 cases: at least 25 each of `billing`, `technical`, `sales`, and 12–22% `ambiguous`. Vary length hard — some under 120 characters, some over 400.

Ambiguous cases must be *genuinely* ambiguous: a billing complaint whose real cause is a bug, an upgrade enquiry that is actually a support problem. Short is not the same as ambiguous.

```json
{
  "version": 1,
  "cases": [
    {
      "id": "billing-001",
      "expected": "billing",
      "subject": "Charged twice for March",
      "body": "Hi there,\n\nWe've just been going through the card statement and it looks like invoice 4471 was taken twice on the 3rd. Same amount, same reference. Could you take a look and refund the duplicate?\n\nThanks,\nPriya"
    },
    {
      "id": "technical-001",
      "expected": "technical",
      "subject": "webhooks stopped firing",
      "body": "our webhooks just stopped. nothing since about 2am. endpoint is up, we can curl it fine. nothing in your status page either?"
    },
    {
      "id": "sales-001",
      "expected": "sales",
      "subject": "Adding 12 seats before end of quarter",
      "body": "Morning — we're onboarding a new team and need another twelve seats added before the end of the quarter. Can you send over pricing for that, and confirm whether it's prorated against our current term? Happy to jump on a call if that's easier.\n\nBest,\nTom Whitfield\nOperations, Marlow & Co"
    },
    {
      "id": "ambiguous-001",
      "expected": "ambiguous",
      "subject": "we're being charged for something that doesn't work",
      "body": "I'm not sure who to send this to. We're paying for the premium export add-on but it times out every single time we use it on anything over ~2000 rows. So either the feature is broken or we shouldn't be paying for it. Either way I'd like it sorted."
    },
    {
      "id": "ambiguous-002",
      "expected": "ambiguous",
      "subject": "upgrade question",
      "body": "If we move up to the higher tier does that fix the rate limiting we keep hitting, or is that a separate problem?"
    },
    {
      "id": "billing-002",
      "expected": "billing",
      "subject": "VAT number on invoices",
      "body": "can you add our VAT number to future invoices please"
    }
  ]
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m unittest simulation.tests.test_corpus -v`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add simulation/corpus.json simulation/tests/__init__.py simulation/tests/test_corpus.py
git commit -m "Add labelled corpus of synthetic support emails"
```

---

## Task 2: Seeded generator

**Files:**
- Create: `simulation/generator.py`
- Create: `simulation/__init__.py` (empty)
- Test: `simulation/tests/test_generator.py`

**Interfaces:**
- Consumes: `corpus.json` from Task 1.
- Produces:
  - `class Case` — frozen dataclass with fields `case_id: str`, `corpus_id: str`, `expected: str`, `subject: str`, `body: str`.
  - `load_corpus(path: str) -> List[dict]`
  - `generate(cases: List[dict], count: int, seed: int) -> List[Case]`

  Task 5 calls `load_corpus` then `generate`. Task 3 consumes `Case.case_id`, `.corpus_id`, `.expected`, `.subject`.

- [ ] **Step 1: Write the failing test**

```python
# simulation/tests/test_generator.py
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest simulation.tests.test_generator -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'simulation.generator'`.

- [ ] **Step 3: Write the implementation**

```python
# simulation/generator.py
"""Turns the labelled corpus into any number of cases, reproducibly.

Pure: no network, no clock, no global random state. Everything derives from
the seed, so a run can be replayed exactly.
"""
import json
import random
from dataclasses import dataclass
from typing import List

GREETINGS = ["", "Hi,\n\n", "Hello,\n\n", "Hi there,\n\n", "Morning,\n\n"]
SIGNOFFS = ["", "\n\nThanks", "\n\nThanks!", "\n\nCheers", "\n\nBest regards", "\n\nRegards"]
# Applied rarely. Real mail has typos; uniform spelling flatters the model.
TYPOS = [("the ", "teh "), ("and ", "adn "), ("you ", "yuo "), ("with ", "wiht ")]


@dataclass(frozen=True)
class Case:
    case_id: str
    corpus_id: str
    expected: str
    subject: str
    body: str


def load_corpus(path: str) -> List[dict]:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)["cases"]


def _perturb(body: str, rng: random.Random) -> str:
    text = body
    if rng.random() < 0.5:
        text = rng.choice(GREETINGS) + text
    if rng.random() < 0.5:
        text = text + rng.choice(SIGNOFFS)
    if rng.random() < 0.15:
        find, replace = rng.choice(TYPOS)
        text = text.replace(find, replace, 1)
    if rng.random() < 0.2:
        text = text.replace("\n\n", "\n")
    return text


def generate(cases: List[dict], count: int, seed: int) -> List[Case]:
    """Sample `count` cases with replacement, lightly perturbing each body.

    Perturbation only touches greetings, sign-offs, spacing and the odd typo,
    so the correct team for a mail is never altered.
    """
    if not cases:
        raise ValueError("corpus is empty")
    if count < 1:
        raise ValueError("count must be at least 1")

    rng = random.Random(seed)
    generated = []
    for index in range(count):
        source = rng.choice(cases)
        generated.append(
            Case(
                case_id="%s#%d" % (source["id"], index),
                corpus_id=source["id"],
                expected=source["expected"],
                subject=source["subject"],
                body=_perturb(source["body"], rng),
            )
        )
    return generated
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m unittest simulation.tests.test_generator -v`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add simulation/__init__.py simulation/generator.py simulation/tests/test_generator.py
git commit -m "Add seeded case generator"
```

---

## Task 3: Outcome taxonomy, summary, and threshold sweep

**Files:**
- Create: `simulation/report.py`
- Test: `simulation/tests/test_report.py`

**Interfaces:**
- Consumes: nothing at runtime; operates on `Observation` values built by Task 5.
- Produces:
  - `TEAMS: Tuple[str, ...]`, `AMBIGUOUS: str`, `UNDECIDED: str`, `OUTCOMES: Tuple[str, ...]`
  - `class Observation` — frozen dataclass: `case_id: str`, `corpus_id: str`, `expected: str`, `subject: str`, `sent_at: float`, `observed_at: Optional[float]`, `choice: Optional[str]`, `decided: Optional[bool]`, `jev_choice: Optional[str]`, `confidence: Optional[float]`, `probabilities: dict`
  - `classify(expected: str, decided: bool, jev_choice: str) -> str`
  - `classify_at(expected: str, confidence: float, jev_choice: str, threshold: float) -> str`
  - `summarize(observations: List[Observation], requested_rate: float, elapsed: float) -> dict`
  - `sweep(observations: List[Observation], thresholds: List[float]) -> List[dict]`
  - `default_thresholds() -> List[float]`
  - `write_csv(observations: List[Observation], path: str) -> None`
  - `format_report(summary: dict, sweep_rows: List[dict], poll_interval: float) -> str`

  Task 5 calls `summarize`, `sweep`, `write_csv`, `format_report`.

- [ ] **Step 1: Write the failing test**

```python
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest simulation.tests.test_report -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'simulation.report'`.

- [ ] **Step 3: Write the implementation**

```python
# simulation/report.py
"""Outcome classification, summary statistics, and the offline threshold sweep.

Pure: no network, no clock. This is where a silent bug would produce a
confident and wrong recommendation, so it is the most heavily tested module.
"""
import csv
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

TEAMS: Tuple[str, ...] = ("billing", "technical", "sales")
AMBIGUOUS = "ambiguous"
UNDECIDED = "undecided"

OUTCOMES: Tuple[str, ...] = (
    "correct_route",
    "misroute",
    "overconfident",
    "warranted_escalation",
    "wasted_escalation",
    "saved_escalation",
)

ESCALATED = ("warranted_escalation", "wasted_escalation", "saved_escalation")


@dataclass(frozen=True)
class Observation:
    case_id: str
    corpus_id: str
    expected: str
    subject: str
    sent_at: float
    observed_at: Optional[float]
    choice: Optional[str]
    decided: Optional[bool]
    jev_choice: Optional[str]
    confidence: Optional[float]
    probabilities: dict

    @property
    def resolved(self) -> bool:
        return self.observed_at is not None and self.confidence is not None


def classify(expected: str, decided: bool, jev_choice: str) -> str:
    """Place one case in the six-way taxonomy from the design spec."""
    ambiguous = expected == AMBIGUOUS
    if decided:
        if ambiguous:
            return "overconfident"
        return "correct_route" if jev_choice == expected else "misroute"
    if ambiguous:
        return "warranted_escalation"
    return "wasted_escalation" if jev_choice == expected else "saved_escalation"


def classify_at(expected: str, confidence: float, jev_choice: str, threshold: float) -> str:
    """Classify as if the connector had run with `threshold`. Boundary is >=."""
    return classify(expected, confidence >= threshold, jev_choice)


def default_thresholds() -> List[float]:
    return [round(0.50 + 0.05 * step, 2) for step in range(10)]


def _percentile(values: List[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round(fraction * (len(ordered) - 1))))
    return ordered[index]


def summarize(observations: List[Observation], requested_rate: float, elapsed: float) -> dict:
    resolved = [o for o in observations if o.resolved]
    counts = {outcome: 0 for outcome in OUTCOMES}
    confusion: Dict[Tuple[str, str], int] = {}
    latencies = []

    for observation in resolved:
        outcome = classify(observation.expected, bool(observation.decided), observation.jev_choice)
        counts[outcome] += 1
        key = (observation.expected, observation.jev_choice)
        confusion[key] = confusion.get(key, 0) + 1
        latencies.append((observation.observed_at - observation.sent_at) * 1000.0)

    total = len(resolved)
    escalations = sum(counts[name] for name in ESCALATED)

    def rate(count: int) -> float:
        return (count / total) if total else 0.0

    return {
        "sent": len(observations),
        "resolved": total,
        "unresolved": len(observations) - total,
        "outcomes": counts,
        "confusion": confusion,
        "escalation_rate": rate(escalations),
        "misroute_rate": rate(counts["misroute"]),
        "requested_rate": requested_rate,
        "achieved_rate": (total / elapsed) if elapsed > 0 else 0.0,
        "elapsed": elapsed,
        "latency_ms": {
            "p50": _percentile(latencies, 0.50),
            "p95": _percentile(latencies, 0.95),
            "p99": _percentile(latencies, 0.99),
        },
    }


def sweep(observations: List[Observation], thresholds: List[float]) -> List[dict]:
    """What each threshold would have produced, from a single run's data.

    Possible only because the connector keeps `jevChoice` and `confidence`
    even when it returns `undecided`.
    """
    resolved = [o for o in observations if o.resolved]
    rows = []
    for threshold in thresholds:
        counts = {outcome: 0 for outcome in OUTCOMES}
        for observation in resolved:
            counts[
                classify_at(
                    observation.expected,
                    observation.confidence,
                    observation.jev_choice,
                    threshold,
                )
            ] += 1
        total = len(resolved)
        escalations = sum(counts[name] for name in ESCALATED)
        rows.append(
            {
                "threshold": threshold,
                "outcomes": counts,
                "escalation_rate": (escalations / total) if total else 0.0,
                "misroute_rate": (counts["misroute"] / total) if total else 0.0,
                # Overconfident is excluded: an ambiguous case has no correct
                # team, so committing cannot be called wrong on this evidence.
                "total_error": counts["misroute"] + counts["wasted_escalation"],
            }
        )
    return rows


CSV_FIELDS = [
    "caseId", "corpusId", "expected", "subject",
    "sentAt", "observedAt", "latencyMs",
    "choice", "decided", "jevChoice", "confidence",
    "p_billing", "p_technical", "p_sales", "outcome",
]


def write_csv(observations: List[Observation], path: str) -> None:
    with open(path, "w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_FIELDS)
        writer.writeheader()
        for observation in observations:
            probabilities = observation.probabilities or {}
            outcome = ""
            latency = ""
            if observation.resolved:
                outcome = classify(
                    observation.expected, bool(observation.decided), observation.jev_choice
                )
                latency = round((observation.observed_at - observation.sent_at) * 1000.0, 1)
            writer.writerow(
                {
                    "caseId": observation.case_id,
                    "corpusId": observation.corpus_id,
                    "expected": observation.expected,
                    "subject": observation.subject,
                    "sentAt": round(observation.sent_at, 3),
                    "observedAt": round(observation.observed_at, 3) if observation.observed_at else "",
                    "latencyMs": latency,
                    "choice": observation.choice or "",
                    "decided": observation.decided if observation.decided is not None else "",
                    "jevChoice": observation.jev_choice or "",
                    "confidence": observation.confidence if observation.confidence is not None else "",
                    "p_billing": probabilities.get("billing", ""),
                    "p_technical": probabilities.get("technical", ""),
                    "p_sales": probabilities.get("sales", ""),
                    "outcome": outcome,
                }
            )


def format_report(summary: dict, sweep_rows: List[dict], poll_interval: float) -> str:
    lines = []
    add = lines.append

    add("")
    add("Throughput")
    add("  requested      %.1f/s" % summary["requested_rate"])
    add("  achieved       %.1f/s  (%d resolved in %.1fs)"
        % (summary["achieved_rate"], summary["resolved"], summary["elapsed"]))
    add("")
    add("Latency  (send to observation; includes up to %.1fs of poll delay)" % poll_interval)
    add("  p50 %.0fms   p95 %.0fms   p99 %.0fms"
        % (summary["latency_ms"]["p50"], summary["latency_ms"]["p95"], summary["latency_ms"]["p99"]))
    add("")
    add("Outcomes  (%d resolved, %d unresolved)" % (summary["resolved"], summary["unresolved"]))
    total = summary["resolved"] or 1
    for outcome in OUTCOMES:
        count = summary["outcomes"][outcome]
        add("  %-22s %5d  %5.1f%%" % (outcome, count, 100.0 * count / total))
    add("")
    add("  escalation rate  %.1f%%" % (100.0 * summary["escalation_rate"]))
    add("  misroute rate    %.1f%%" % (100.0 * summary["misroute_rate"]))
    add("")
    add("Confusion  (expected -> Jev's pick)")
    for expected in list(TEAMS) + [AMBIGUOUS]:
        row = ["%s %d" % (pick, count)
               for (exp, pick), count in sorted(summary["confusion"].items())
               if exp == expected]
        if row:
            add("  %-10s %s" % (expected, "   ".join(row)))
    add("")
    add("Threshold sweep  (what each threshold would have produced)")
    add("  %-10s %-10s %-10s %s" % ("threshold", "misroute", "escalation", "total error"))
    for row in sweep_rows:
        add("  %-10.2f %-10.1f %-10.1f %d"
            % (row["threshold"], 100.0 * row["misroute_rate"],
               100.0 * row["escalation_rate"], row["total_error"]))
    if sweep_rows:
        best_misroute = min(sweep_rows, key=lambda r: (r["misroute_rate"], r["threshold"]))
        best_total = min(sweep_rows, key=lambda r: (r["total_error"], r["threshold"]))
        add("")
        add("  lowest misroute rate at t=%.2f" % best_misroute["threshold"])
        add("  lowest total error   at t=%.2f" % best_total["threshold"])
        add("  The trade-off between a misroute and an escalation is a business")
        add("  cost, so no single threshold is declared optimal here.")
    add("")
    return "\n".join(lines)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m unittest simulation.tests.test_report -v`
Expected: PASS, 22 tests.

- [ ] **Step 5: Commit**

```bash
git add simulation/report.py simulation/tests/test_report.py
git commit -m "Add outcome taxonomy, summary, and threshold sweep"
```

---

## Task 4: Load-test BPMN and its contract test

**Files:**
- Create: `examples/support-ticket-loadtest.bpmn`
- Modify: `src/test/java/ai/typesafe/camunda/jev/ArtifactsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: process id `support-ticket-loadtest`, started with variables `ticket`, `expected`, `caseId`, `threshold`. Task 5 creates instances of it.

- [ ] **Step 1: Write the failing test**

Append these methods to `ArtifactsTest`, and add `private static final Path LOADTEST = Path.of("examples/support-ticket-loadtest.bpmn");` beside the existing `BPMN` field.

```java
  @Test
  void loadTestProcessBranchesOnEveryOptionPlusUndecided() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(LOADTEST.toFile());

    NodeList expressions = document.getElementsByTagNameNS("*", "conditionExpression");
    List<String> conditions = new ArrayList<>();
    for (int i = 0; i < expressions.getLength(); i++) {
      conditions.add(expressions.item(i).getTextContent().trim());
    }

    assertThat(conditions)
        .containsExactlyInAnyOrder(
            "=jevResult.choice = \"billing\"",
            "=jevResult.choice = \"technical\"",
            "=jevResult.choice = \"sales\"",
            "=jevResult.choice = \"undecided\"");
  }

  @Test
  void loadTestProcessAutoClosesConfidentRoutesAndParksEscalations() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(LOADTEST.toFile());

    // Exactly one user task: the human queue. Confident routes must not park,
    // or throughput would measure nothing but an unattended queue.
    NodeList userTasks = document.getElementsByTagNameNS("*", "userTask");
    assertThat(userTasks.getLength()).isEqualTo(1);
    assertThat(((Element) userTasks.item(0)).getAttribute("id")).isEqualTo("Task_HumanReview");

    NodeList definitions = document.getElementsByTagNameNS("*", "taskDefinition");
    assertThat(definitions.getLength()).isEqualTo(1);
    assertThat(((Element) definitions.item(0)).getAttribute("type"))
        .isEqualTo(JevRouterFunction.TYPE);
  }

  @Test
  void loadTestProcessNeverSendsGroundTruthToJev() throws Exception {
    // `expected` is the label the report scores against. If it reached the
    // model, every accuracy number would be invalid but still look plausible.
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(LOADTEST.toFile());

    NodeList inputs = document.getElementsByTagNameNS("*", "input");
    assertThat(inputs.getLength()).isGreaterThan(0);
    for (int i = 0; i < inputs.getLength(); i++) {
      Element input = (Element) inputs.item(i);
      assertThat(input.getAttribute("source")).doesNotContain("expected");
      assertThat(input.getAttribute("target")).isNotEqualTo("expected");
    }
  }

  @Test
  void loadTestProcessTakesThresholdFromAVariable() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(LOADTEST.toFile());

    NodeList inputs = document.getElementsByTagNameNS("*", "input");
    String thresholdSource = null;
    for (int i = 0; i < inputs.getLength(); i++) {
      Element input = (Element) inputs.item(i);
      if ("threshold".equals(input.getAttribute("target"))) {
        thresholdSource = input.getAttribute("source");
      }
    }
    // Set per run by the feeder, never hard-coded in the diagram.
    assertThat(thresholdSource).isEqualTo("=threshold");
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -B test -Dtest=ArtifactsTest`
Expected: FAIL — `examples/support-ticket-loadtest.bpmn` does not exist (`FileNotFoundException`).

- [ ] **Step 3: Create the BPMN**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xmlns:zeebe="http://camunda.org/schema/zeebe/1.0"
                  xmlns:modeler="http://camunda.org/schema/modeler/1.0"
                  id="Definitions_JevLoadTest"
                  targetNamespace="http://bpmn.io/schema/bpmn"
                  exporter="Camunda Modeler" exporterVersion="5.0.0"
                  modeler:executionPlatform="Camunda Cloud"
                  modeler:executionPlatformVersion="8.9.0">
  <bpmn:process id="support-ticket-loadtest" name="Support ticket routing (load test)" isExecutable="true">

    <bpmn:startEvent id="StartEvent_Ticket" name="Ticket received">
      <bpmn:outgoing>Flow_ToRouter</bpmn:outgoing>
    </bpmn:startEvent>

    <bpmn:serviceTask id="Task_JevRouter" name="Route ticket"
                      zeebe:modelerTemplate="ai.typesafe.camunda.jev.router.v1">
      <bpmn:extensionElements>
        <zeebe:taskDefinition type="ai.typesafe:jev-router:1" retries="3" />
        <zeebe:ioMapping>
          <zeebe:input source="{{secrets.TYPESAFE_API_KEY}}" target="apiKey" />
          <zeebe:input source="=ticket" target="state" />
          <zeebe:input source="Which team should handle this ticket?" target="question" />
          <zeebe:input source="={billing: &#34;Payments, invoicing, refunds&#34;, technical: &#34;Bugs, outages, integrations&#34;, sales: &#34;Pricing, upgrades, new accounts&#34;}" target="options" />
          <zeebe:input source="=threshold" target="threshold" />
          <zeebe:input source="jev-1.13.0" target="model" />
        </zeebe:ioMapping>
        <zeebe:taskHeaders>
          <zeebe:header key="resultVariable" value="jevResult" />
          <zeebe:header key="elementTemplateId" value="ai.typesafe.camunda.jev.router.v1" />
          <zeebe:header key="elementTemplateVersion" value="1" />
          <zeebe:header key="retryBackoff" value="PT0S" />
        </zeebe:taskHeaders>
      </bpmn:extensionElements>
      <bpmn:incoming>Flow_ToRouter</bpmn:incoming>
      <bpmn:outgoing>Flow_ToGateway</bpmn:outgoing>
    </bpmn:serviceTask>

    <bpmn:exclusiveGateway id="Gateway_Route" name="Which team?">
      <bpmn:incoming>Flow_ToGateway</bpmn:incoming>
      <bpmn:outgoing>Flow_Billing</bpmn:outgoing>
      <bpmn:outgoing>Flow_Technical</bpmn:outgoing>
      <bpmn:outgoing>Flow_Sales</bpmn:outgoing>
      <bpmn:outgoing>Flow_Undecided</bpmn:outgoing>
    </bpmn:exclusiveGateway>

    <bpmn:endEvent id="End_Billing" name="Routed to billing">
      <bpmn:incoming>Flow_Billing</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:endEvent id="End_Technical" name="Routed to technical">
      <bpmn:incoming>Flow_Technical</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:endEvent id="End_Sales" name="Routed to sales">
      <bpmn:incoming>Flow_Sales</bpmn:incoming>
    </bpmn:endEvent>

    <bpmn:userTask id="Task_HumanReview" name="Human review">
      <bpmn:extensionElements>
        <zeebe:userTask />
      </bpmn:extensionElements>
      <bpmn:incoming>Flow_Undecided</bpmn:incoming>
      <bpmn:outgoing>Flow_ReviewEnd</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:endEvent id="End_Reviewed" name="Reviewed by a human">
      <bpmn:incoming>Flow_ReviewEnd</bpmn:incoming>
    </bpmn:endEvent>

    <bpmn:sequenceFlow id="Flow_ToRouter" sourceRef="StartEvent_Ticket" targetRef="Task_JevRouter" />
    <bpmn:sequenceFlow id="Flow_ToGateway" sourceRef="Task_JevRouter" targetRef="Gateway_Route" />
    <bpmn:sequenceFlow id="Flow_Billing" name="billing" sourceRef="Gateway_Route" targetRef="End_Billing">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">=jevResult.choice = "billing"</bpmn:conditionExpression>
    </bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="Flow_Technical" name="technical" sourceRef="Gateway_Route" targetRef="End_Technical">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">=jevResult.choice = "technical"</bpmn:conditionExpression>
    </bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="Flow_Sales" name="sales" sourceRef="Gateway_Route" targetRef="End_Sales">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">=jevResult.choice = "sales"</bpmn:conditionExpression>
    </bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="Flow_Undecided" name="undecided" sourceRef="Gateway_Route" targetRef="Task_HumanReview">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">=jevResult.choice = "undecided"</bpmn:conditionExpression>
    </bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="Flow_ReviewEnd" sourceRef="Task_HumanReview" targetRef="End_Reviewed" />
  </bpmn:process>

  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="support-ticket-loadtest">
      <bpmndi:BPMNShape id="Shape_Start" bpmnElement="StartEvent_Ticket">
        <dc:Bounds x="160" y="262" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Router" bpmnElement="Task_JevRouter">
        <dc:Bounds x="250" y="240" width="100" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Gateway" bpmnElement="Gateway_Route" isMarkerVisible="true">
        <dc:Bounds x="415" y="255" width="50" height="50" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_EndBilling" bpmnElement="End_Billing">
        <dc:Bounds x="562" y="102" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_EndTechnical" bpmnElement="End_Technical">
        <dc:Bounds x="562" y="202" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_EndSales" bpmnElement="End_Sales">
        <dc:Bounds x="562" y="302" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Review" bpmnElement="Task_HumanReview">
        <dc:Bounds x="530" y="390" width="100" height="80" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_EndReviewed" bpmnElement="End_Reviewed">
        <dc:Bounds x="702" y="412" width="36" height="36" />
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="Edge_ToRouter" bpmnElement="Flow_ToRouter">
        <di:waypoint x="196" y="280" /><di:waypoint x="250" y="280" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Edge_ToGateway" bpmnElement="Flow_ToGateway">
        <di:waypoint x="350" y="280" /><di:waypoint x="415" y="280" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Edge_Billing" bpmnElement="Flow_Billing">
        <di:waypoint x="440" y="255" /><di:waypoint x="440" y="120" /><di:waypoint x="562" y="120" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Edge_Technical" bpmnElement="Flow_Technical">
        <di:waypoint x="440" y="255" /><di:waypoint x="440" y="220" /><di:waypoint x="562" y="220" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Edge_Sales" bpmnElement="Flow_Sales">
        <di:waypoint x="440" y="305" /><di:waypoint x="440" y="320" /><di:waypoint x="562" y="320" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Edge_Undecided" bpmnElement="Flow_Undecided">
        <di:waypoint x="440" y="305" /><di:waypoint x="440" y="430" /><di:waypoint x="530" y="430" />
      </bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="Edge_ReviewEnd" bpmnElement="Flow_ReviewEnd">
        <di:waypoint x="630" y="430" /><di:waypoint x="702" y="430" />
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -B test -Dtest=ArtifactsTest`
Expected: PASS, 8 tests (4 existing + 4 new).

Then confirm the XML is well formed: `xmllint --noout examples/support-ticket-loadtest.bpmn`

- [ ] **Step 5: Commit**

```bash
git add examples/support-ticket-loadtest.bpmn src/test/java/ai/typesafe/camunda/jev/ArtifactsTest.java
git commit -m "Add load-test process with auto-closing routes"
```

---

## Task 5: Feeder, collector, and CLI

**Files:**
- Create: `simulation/loadtest.py`
- Test: `simulation/tests/test_loadtest.py`

**Interfaces:**
- Consumes: `generator.load_corpus`, `generator.generate`, `generator.Case`; `report.Observation`, `report.summarize`, `report.sweep`, `report.write_csv`, `report.format_report`, `report.default_thresholds`.
- Produces:
  - `class RateLimiter` — `__init__(self, rate: float, now=time.monotonic, sleep=time.sleep)`, `acquire() -> None`
  - `class CamundaClient` — `__init__(self, base_url: str, post=None)`, `create_instance(process_id: str, variables: dict) -> str`, `find_results(keys: List[str]) -> Dict[str, dict]`
  - `main(argv: List[str]) -> int`

  `post` is the injection point for tests: a callable `(url: str, payload: dict) -> dict`.

- [ ] **Step 1: Write the failing test**

```python
# simulation/tests/test_loadtest.py
import unittest

from simulation.loadtest import CamundaClient, RateLimiter


class FakeClock:
    def __init__(self):
        self.t = 0.0

    def now(self):
        return self.t

    def sleep(self, seconds):
        self.t += seconds


class RateLimiterTest(unittest.TestCase):
    def test_first_acquire_is_immediate(self):
        clock = FakeClock()
        limiter = RateLimiter(10.0, now=clock.now, sleep=clock.sleep)
        limiter.acquire()
        self.assertEqual(clock.t, 0.0)

    def test_subsequent_acquires_are_paced(self):
        clock = FakeClock()
        limiter = RateLimiter(10.0, now=clock.now, sleep=clock.sleep)
        for _ in range(5):
            limiter.acquire()
        # 5 acquires at 10/s occupy 4 intervals of 0.1s.
        self.assertAlmostEqual(clock.t, 0.4, places=6)

    def test_a_slow_caller_is_never_made_to_wait(self):
        clock = FakeClock()
        limiter = RateLimiter(10.0, now=clock.now, sleep=clock.sleep)
        limiter.acquire()
        clock.t += 5.0
        limiter.acquire()
        self.assertAlmostEqual(clock.t, 5.0, places=6)

    def test_rate_must_be_positive(self):
        with self.assertRaises(ValueError):
            RateLimiter(0.0)


class RecordingPost:
    def __init__(self, responses):
        self.responses = responses
        self.calls = []

    def __call__(self, url, payload):
        self.calls.append((url, payload))
        return self.responses.pop(0)


class CamundaClientTest(unittest.TestCase):
    def test_create_instance_sends_process_id_and_variables(self):
        post = RecordingPost([{"processInstanceKey": "42"}])
        client = CamundaClient("http://c:8080", post=post)

        key = client.create_instance("support-ticket-loadtest", {"caseId": "a#1"})

        self.assertEqual(key, "42")
        url, payload = post.calls[0]
        self.assertEqual(url, "http://c:8080/v2/process-instances")
        self.assertEqual(payload["processDefinitionId"], "support-ticket-loadtest")
        self.assertEqual(payload["variables"]["caseId"], "a#1")

    def test_find_results_parses_the_json_string_value(self):
        post = RecordingPost([{
            "items": [{"processInstanceKey": "42",
                       "value": '{"choice":"billing","decided":true,"confidence":0.8}'}]
        }])
        client = CamundaClient("http://c:8080", post=post)

        results = client.find_results(["42"])

        self.assertEqual(results["42"]["choice"], "billing")
        self.assertTrue(results["42"]["decided"])

    def test_find_results_filters_on_jev_result(self):
        post = RecordingPost([{"items": []}])
        client = CamundaClient("http://c:8080", post=post)
        client.find_results(["1"])
        _, payload = post.calls[0]
        self.assertEqual(payload["filter"]["name"], "jevResult")
        self.assertEqual(payload["filter"]["processInstanceKey"]["$in"], ["1"])

    def test_find_results_chunks_large_key_sets(self):
        post = RecordingPost([{"items": []}, {"items": []}, {"items": []}])
        client = CamundaClient("http://c:8080", post=post)

        client.find_results([str(n) for n in range(250)])

        self.assertEqual(len(post.calls), 3)
        self.assertEqual(len(post.calls[0][1]["filter"]["processInstanceKey"]["$in"]), 100)
        self.assertEqual(len(post.calls[2][1]["filter"]["processInstanceKey"]["$in"]), 50)

    def test_find_results_returns_empty_for_no_keys(self):
        post = RecordingPost([])
        client = CamundaClient("http://c:8080", post=post)
        self.assertEqual(client.find_results([]), {})
        self.assertEqual(post.calls, [])


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m unittest simulation.tests.test_loadtest -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'simulation.loadtest'`.

- [ ] **Step 3: Write the implementation**

```python
# simulation/loadtest.py
"""Feeds generated tickets into Camunda at a controlled rate and collects results.

All network and timing lives here; the maths lives in report.py and the data
shaping in generator.py, both of which are pure and separately tested.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from typing import Dict, List, Optional

from simulation.generator import generate, load_corpus
from simulation.report import (
    UNDECIDED,
    Observation,
    default_thresholds,
    format_report,
    summarize,
    sweep,
    write_csv,
)

PROCESS_ID = "support-ticket-loadtest"
CHUNK = 100


class RateLimiter:
    """Paces calls to `rate` per second. A caller slower than the rate is never delayed."""

    def __init__(self, rate: float, now=time.monotonic, sleep=time.sleep):
        if rate <= 0:
            raise ValueError("rate must be positive")
        self._interval = 1.0 / rate
        self._now = now
        self._sleep = sleep
        self._next: Optional[float] = None

    def acquire(self) -> None:
        current = self._now()
        if self._next is None:
            self._next = current + self._interval
            return
        wait = self._next - current
        if wait > 0:
            self._sleep(wait)
            self._next += self._interval
        else:
            self._next = current + self._interval


def _http_post(url: str, payload: dict) -> dict:
    data = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json", "Accept": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        body = response.read().decode("utf-8")
    return json.loads(body) if body else {}


class CamundaClient:
    def __init__(self, base_url: str, post=None):
        self.base_url = base_url.rstrip("/")
        self._post = post or _http_post

    def create_instance(self, process_id: str, variables: dict) -> str:
        result = self._post(
            "%s/v2/process-instances" % self.base_url,
            {"processDefinitionId": process_id, "variables": variables},
        )
        return result["processInstanceKey"]

    def find_results(self, keys: List[str]) -> Dict[str, dict]:
        """Fetch jevResult for the given instances, in chunks.

        Reads the variable rather than waiting for completion: a confident route
        completes, but an escalation parks at the human task and never would.
        """
        found: Dict[str, dict] = {}
        for start in range(0, len(keys), CHUNK):
            batch = keys[start:start + CHUNK]
            result = self._post(
                "%s/v2/variables/search" % self.base_url,
                {"filter": {"name": "jevResult", "processInstanceKey": {"$in": batch}}},
            )
            for item in result.get("items", []):
                value = item.get("value")
                if not value:
                    continue
                parsed = json.loads(value) if isinstance(value, str) else value
                found[str(item["processInstanceKey"])] = parsed
        return found


def _run(args) -> int:
    corpus_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "corpus.json")
    cases = generate(load_corpus(corpus_path), args.count, args.seed)
    client = CamundaClient(args.base_url)
    limiter = RateLimiter(args.rate)

    pending: Dict[str, Observation] = {}
    observations: List[Observation] = []
    started = time.monotonic()
    failures = 0

    print("Feeding %d tickets at %.1f/s to %s" % (args.count, args.rate, args.base_url))

    for index, case in enumerate(cases, start=1):
        limiter.acquire()
        variables = {
            "ticket": {"subject": case.subject, "body": case.body},
            "expected": case.expected,
            "caseId": case.case_id,
            "threshold": args.threshold,
        }
        try:
            key = client.create_instance(PROCESS_ID, variables)
        except (urllib.error.URLError, KeyError) as error:
            failures += 1
            if failures <= 3:
                print("  create failed: %s" % error, file=sys.stderr)
            continue

        pending[key] = Observation(
            case_id=case.case_id, corpus_id=case.corpus_id, expected=case.expected,
            subject=case.subject, sent_at=time.monotonic(), observed_at=None,
            choice=None, decided=None, jev_choice=None, confidence=None, probabilities={},
        )

        if index % 25 == 0:
            _drain(client, pending, observations)
            _progress(index, args.count, observations, started)

    deadline = time.monotonic() + args.timeout
    while pending and time.monotonic() < deadline:
        time.sleep(args.poll_interval)
        _drain(client, pending, observations)
        _progress(args.count, args.count, observations, started)

    observations.extend(pending.values())
    elapsed = time.monotonic() - started

    print("\n")
    summary = summarize(observations, args.rate, elapsed)
    print(format_report(summary, sweep(observations, default_thresholds()), args.poll_interval))

    if failures:
        print("  %d instance creations failed" % failures)

    write_csv(observations, args.out)
    print("  per-case results written to %s\n" % args.out)
    return 0


def _drain(client: CamundaClient, pending: Dict[str, Observation], done: List[Observation]) -> None:
    if not pending:
        return
    results = client.find_results(list(pending.keys()))
    seen = time.monotonic()
    for key, payload in results.items():
        observation = pending.pop(key, None)
        if observation is None:
            continue
        done.append(
            Observation(
                case_id=observation.case_id, corpus_id=observation.corpus_id,
                expected=observation.expected, subject=observation.subject,
                sent_at=observation.sent_at, observed_at=seen,
                choice=payload.get("choice"), decided=payload.get("decided"),
                jev_choice=payload.get("jevChoice"), confidence=payload.get("confidence"),
                probabilities=payload.get("probabilities") or {},
            )
        )


def _progress(sent: int, total: int, done: List[Observation], started: float) -> None:
    elapsed = max(time.monotonic() - started, 1e-6)
    escalated = sum(1 for o in done if o.choice == UNDECIDED)
    sys.stdout.write(
        "\r  sent %d/%d   resolved %d   escalated %d   %.1f/s   "
        % (sent, total, len(done), escalated, len(done) / elapsed)
    )
    sys.stdout.flush()


def main(argv: List[str]) -> int:
    parser = argparse.ArgumentParser(description="Load-simulate the Jev Router connector.")
    parser.add_argument("--count", type=int, default=2000)
    parser.add_argument("--rate", type=float, default=10.0)
    parser.add_argument("--threshold", type=float, default=0.7)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--poll-interval", type=float, default=2.0, dest="poll_interval")
    parser.add_argument("--timeout", type=float, default=300.0)
    parser.add_argument("--out", default="results.csv")
    parser.add_argument("--base-url", default="http://localhost:8080", dest="base_url")
    return _run(parser.parse_args(argv))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 -m unittest simulation.tests.test_loadtest -v`
Expected: PASS, 9 tests.

Then the whole Python suite: `python3 -m unittest discover -s simulation -p 'test_*.py' -t . -v`
Expected: PASS, 47 tests.

- [ ] **Step 5: Commit**

```bash
git add simulation/loadtest.py simulation/tests/test_loadtest.py
git commit -m "Add rate-limited feeder and batch-polling collector"
```

---

## Task 6: Wire into ./jev, CI, and the README

**Files:**
- Modify: `jev`
- Modify: `.github/workflows/ci.yml`
- Modify: `README.md`

**Interfaces:**
- Consumes: `simulation/loadtest.py` `main()` from Task 5; `examples/support-ticket-loadtest.bpmn` from Task 4.
- Produces: `./jev simulate` subcommand.

- [ ] **Step 1: Add the subcommand**

In `jev`, add this function just before `cmd_logs`:

```bash
cmd_simulate() {
  need_key
  have python3 || die "python3 is required for ./jev simulate"
  curl -sf -o /dev/null --max-time 5 "$REST/operate" \
    || die "no Camunda at $REST. Start one, or use your own cluster."

  # The load-test process is separate from the demo process; deploy it if absent.
  step "Deploying the load-test process"
  curl -sf -o /dev/null -X POST "$REST/v2/deployments" \
      -F "resources=@$ROOT/examples/support-ticket-loadtest.bpmn" \
    || die "could not deploy examples/support-ticket-loadtest.bpmn"
  ok "deployed"

  (cd "$ROOT" && python3 -m simulation.loadtest "$@")
}
```

Register it in the `case` block beside the other commands:

```bash
  simulate) shift; cmd_simulate "$@" ;;
```

And add to `cmd_help`, under the "Local stack" heading:

```
  simulate [opts]   load-simulate: feed N labelled tickets and report
```

- [ ] **Step 2: Verify the subcommand is wired**

Run: `bash -n jev && ./jev help`
Expected: no syntax errors; `simulate` appears in the help output.

- [ ] **Step 3: Add the Python tests to CI**

In `.github/workflows/ci.yml`, inside the `build` job, after the "Check element template is up to date" step:

```yaml
      - name: Run simulation unit tests
        run: python3 -m unittest discover -s simulation -p 'test_*.py' -t . -v
```

The runner ships Python 3, and the suite has no third-party dependencies, so no setup step is needed. The simulation itself is not run in CI: it needs a live cluster and an API key.

- [ ] **Step 4: Document it in the README**

Add to the commands table, under the local-stack rows:

```markdown
| `./jev simulate [opts]` | Feed N labelled tickets through the load-test process and report |
```

And add this section after "Setting the threshold":

```markdown
## Measuring it under load

`./jev simulate` feeds synthetic, **labelled** tickets through a separate load-test process and
reports what happened:

```bash
./jev simulate --count 2000 --rate 10
```

Because each ticket carries the team it should have gone to, the report separates outcomes that an
unlabelled run cannot tell apart — an escalation that saved you from a misroute looks identical to
one that was simply wasted, unless you know the right answer.

It also sweeps the threshold offline. The connector keeps `jevChoice` and `confidence` even when it
escalates, so a single run shows what every threshold from 0.50 to 0.95 would have produced. That is
the number this README tells you to tune, and one run answers it for the whole range.

A 2000-ticket run costs roughly $0.03 in Jev tokens and stays well inside the documented rate limit.

Reported latency is measured from send to observation and therefore includes polling delay; the poll
interval is printed alongside it. It is not a measure of connector latency.
```

- [ ] **Step 5: Run the full suite and commit**

Run:
```bash
mvn -B clean verify
python3 -m unittest discover -s simulation -p 'test_*.py' -t . -v
```
Expected: Java 38 tests pass (34 existing + 4 from Task 4); Python 47 tests pass.

```bash
git add jev .github/workflows/ci.yml README.md
git commit -m "Wire ./jev simulate, CI, and README"
```

---

## Manual verification

Not part of CI; run once against a live cluster with a real key.

- [ ] Small smoke run: `./jev simulate --count 20 --rate 5 --out /tmp/smoke.csv`
  - Expect: a report with 20 resolved, non-zero `correct_route`, and a CSV of 21 lines.
- [ ] Confirm escalations parked: Tasklist shows open **Human review** tasks equal to the reported
      escalation count.
- [ ] Confirm ground truth did not leak: pick any instance in Operate, open the Jev Router task's
      input, and check `state` contains only subject and body — no `expected`.
- [ ] Full run: `./jev simulate --count 2000 --rate 10`
