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


def summarize(
    observations: List[Observation],
    requested_rate: float,
    elapsed: float,
    feed_elapsed: float,
) -> dict:
    """Summarise a run.

    `feed_elapsed` covers the feed phase alone and `elapsed` the whole run
    including the polling tail, so send rate and resolution rate are two
    separate numbers. Conflating them makes a run that fed at exactly the
    requested rate look as though it under-ran.
    """
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
        "achieved_send_rate": (len(observations) / feed_elapsed) if feed_elapsed > 0 else 0.0,
        "resolution_rate": (total / elapsed) if elapsed > 0 else 0.0,
        "feed_elapsed": feed_elapsed,
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
    add("  requested send   %.1f/s" % summary["requested_rate"])
    add("  achieved send    %.1f/s  (%d sent in %.1fs of feeding)"
        % (summary["achieved_send_rate"], summary["sent"], summary["feed_elapsed"]))
    add("  resolutions      %.1f/s  (%d resolved in %.1fs, feed plus polling tail)"
        % (summary["resolution_rate"], summary["resolved"], summary["elapsed"]))
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
        # jev_choice can be None on a resolved case (the connector answered but
        # named no team), which would make a plain sort raise at print time.
        row = ["%s %d" % (pick or "none", count)
               for (exp, pick), count in sorted(
                   summary["confusion"].items(), key=lambda item: (item[0][0], item[0][1] or ""))
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
