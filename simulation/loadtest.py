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
