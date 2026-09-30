import argparse
import contextlib
import csv
import io
import os
import tempfile
import threading
import time
import unittest

from simulation.loadtest import CamundaClient, Collector, RateLimiter, _run
from simulation.report import Observation


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

    def test_create_instance_normalises_a_numeric_key(self):
        # find_results keys its dict with str(), so a numeric key here would make
        # every case look unresolved.
        post = RecordingPost([{"processInstanceKey": 42}])
        client = CamundaClient("http://c:8080", post=post)
        self.assertEqual(client.create_instance("p", {}), "42")

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


def _pending_observation(case_id):
    return Observation(
        case_id=case_id, corpus_id="c-1", expected="billing",
        subject="s", sent_at=0.0, observed_at=None,
        choice=None, decided=None, jev_choice=None, confidence=None, probabilities={},
    )


class CollectorTest(unittest.TestCase):
    def test_poll_moves_a_resolved_instance_from_pending_to_done(self):
        post = RecordingPost([{
            "items": [{
                "processInstanceKey": "1",
                "value": (
                    '{"choice":"billing","decided":true,"jevChoice":"billing",'
                    '"confidence":0.9,'
                    '"probabilities":{"billing":0.9,"technical":0.05,"sales":0.05}}'
                ),
            }]
        }])
        collector = Collector(CamundaClient("http://c:8080", post=post), poll_interval=0.001)
        collector.add("1", _pending_observation("a#1"))
        collector.add("2", _pending_observation("a#2"))

        collector.poll_once()

        self.assertEqual(collector.pending_count, 1)
        resolved = [o for o in collector.observations() if o.resolved]
        self.assertEqual(len(resolved), 1)
        self.assertEqual(resolved[0].case_id, "a#1")
        self.assertEqual(resolved[0].choice, "billing")
        self.assertTrue(resolved[0].decided)
        self.assertEqual(resolved[0].jev_choice, "billing")
        self.assertEqual(resolved[0].confidence, 0.9)
        self.assertEqual(
            resolved[0].probabilities,
            {"billing": 0.9, "technical": 0.05, "sales": 0.05},
        )

    def test_poll_tolerates_a_find_results_that_raises(self):
        def raising_post(url, payload):
            raise ValueError("bad json")

        collector = Collector(CamundaClient("http://c:8080", post=raising_post), poll_interval=0.001)
        collector.add("1", _pending_observation("a#1"))

        with contextlib.redirect_stderr(io.StringIO()) as err:
            collector.poll_once()

        self.assertEqual(collector.pending_count, 1)
        self.assertEqual([o for o in collector.observations() if o.resolved], [])
        self.assertIn("poll failed", err.getvalue())

    def test_a_raising_poll_does_not_kill_the_thread(self):
        calls = []

        def flaky_post(url, payload):
            calls.append(payload)
            if len(calls) == 1:
                raise OSError("connection reset")
            return {"items": [{"processInstanceKey": "1", "value": '{"confidence":0.9}'}]}

        collector = Collector(CamundaClient("http://c:8080", post=flaky_post), poll_interval=0.001)
        collector.add("1", _pending_observation("a#1"))

        with contextlib.redirect_stderr(io.StringIO()):
            collector.start()
            deadline = time.monotonic() + 5.0
            while collector.pending_count and time.monotonic() < deadline:
                time.sleep(0.002)
            collector.stop()

        self.assertEqual(collector.pending_count, 0)
        self.assertTrue(collector.observations()[0].resolved)

    def test_an_add_during_a_poll_is_not_lost(self):
        """The feed loop keeps adding while a poll is in flight outside the lock."""
        collector = None

        def post_that_adds_mid_poll(url, payload):
            # Runs while the collector holds no lock, exactly as a real feed would.
            collector.add("2", _pending_observation("a#2"))
            return {"items": [{"processInstanceKey": "1", "value": '{"confidence":0.9}'}]}

        collector = Collector(
            CamundaClient("http://c:8080", post=post_that_adds_mid_poll), poll_interval=0.001
        )
        collector.add("1", _pending_observation("a#1"))

        collector.poll_once()

        case_ids = sorted(o.case_id for o in collector.observations())
        self.assertEqual(case_ids, ["a#1", "a#2"])
        self.assertEqual(collector.pending_count, 1)
        self.assertEqual([o.case_id for o in collector.observations() if o.resolved], ["a#1"])

    def test_stop_returns_promptly_rather_than_waiting_out_the_interval(self):
        collector = Collector(
            CamundaClient("http://c:8080", post=lambda url, payload: {"items": []}),
            poll_interval=30.0,
        )
        collector.add("1", _pending_observation("a#1"))
        collector.start()
        time.sleep(0.01)

        started = time.monotonic()
        collector.stop()

        self.assertLess(time.monotonic() - started, 5.0)

    def test_observations_keep_unresolved_entries(self):
        collector = Collector(
            CamundaClient("http://c:8080", post=lambda url, payload: {"items": []}),
            poll_interval=0.001,
        )
        collector.add("1", _pending_observation("a#1"))
        collector.poll_once()

        observations = collector.observations()
        self.assertEqual(len(observations), 1)
        self.assertFalse(observations[0].resolved)

    def test_concurrent_adds_are_all_recorded(self):
        collector = Collector(
            CamundaClient("http://c:8080", post=lambda url, payload: {"items": []}),
            poll_interval=0.001,
        )

        def feed(offset):
            for n in range(50):
                collector.add(str(offset + n), _pending_observation("a#%d" % (offset + n)))

        collector.start()
        threads = [threading.Thread(target=feed, args=(base,)) for base in (0, 100, 200)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        collector.stop()

        self.assertEqual(len(collector.observations()), 150)


class RunPreservesUnresolvedTest(unittest.TestCase):
    """Exercises _run itself, so deleting the code that keeps unresolved cases fails here."""

    def test_unresolved_cases_reach_the_report_and_the_csv(self):
        created = []

        def post(url, payload):
            if url.endswith("/v2/process-instances"):
                # Returned as a number, as the API sometimes does, to prove
                # create_instance normalises the key the collector matches on.
                created.append(len(created) + 1)
                return {"processInstanceKey": created[-1]}
            keys = payload["filter"]["processInstanceKey"]["$in"]
            # Instance 1 answers; instance 2 never does, so the run must time out
            # on it and still report it rather than dropping it.
            return {"items": [{"processInstanceKey": "1",
                               "value": '{"choice":"billing","decided":true,'
                                        '"jevChoice":"billing","confidence":0.9}'}]
                    if "1" in keys else []}

        with tempfile.TemporaryDirectory() as directory:
            out = os.path.join(directory, "results.csv")
            args = argparse.Namespace(
                count=2, rate=1000.0, threshold=0.7, seed=1, poll_interval=0.005,
                timeout=0.15, out=out, base_url="http://c:8080",
            )
            buffer = io.StringIO()
            with contextlib.redirect_stdout(buffer):
                status = _run(args, client=CamundaClient("http://c:8080", post=post))
            with open(out, encoding="utf-8") as handle:
                rows = list(csv.DictReader(handle))

        self.assertEqual(status, 0)
        self.assertEqual(len(rows), 2)
        self.assertEqual(sum(1 for row in rows if row["observedAt"] == ""), 1)
        text = buffer.getvalue()
        self.assertIn("1 resolved, 1 unresolved", text)
        self.assertIn("achieved send", text)
        self.assertIn("resolutions", text)


if __name__ == "__main__":
    unittest.main()
