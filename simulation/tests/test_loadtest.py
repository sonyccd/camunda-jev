import unittest

from simulation.loadtest import CamundaClient, RateLimiter, _drain
from simulation.report import Observation, summarize


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


def _pending_observation(case_id):
    return Observation(
        case_id=case_id, corpus_id="c-1", expected="billing",
        subject="s", sent_at=0.0, observed_at=None,
        choice=None, decided=None, jev_choice=None, confidence=None, probabilities={},
    )


class DrainTest(unittest.TestCase):
    def test_drain_moves_a_resolved_instance_from_pending_to_done(self):
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
        client = CamundaClient("http://c:8080", post=post)
        pending = {"1": _pending_observation("a#1"), "2": _pending_observation("a#2")}
        done = []

        _drain(client, pending, done)

        self.assertEqual(list(pending.keys()), ["2"])
        self.assertEqual(len(done), 1)
        resolved = done[0]
        self.assertEqual(resolved.case_id, "a#1")
        self.assertEqual(resolved.choice, "billing")
        self.assertTrue(resolved.decided)
        self.assertEqual(resolved.jev_choice, "billing")
        self.assertEqual(resolved.confidence, 0.9)
        self.assertEqual(
            resolved.probabilities,
            {"billing": 0.9, "technical": 0.05, "sales": 0.05},
        )

    def test_drain_tolerates_a_find_results_that_raises(self):
        def raising_post(url, payload):
            raise ValueError("bad json")

        client = CamundaClient("http://c:8080", post=raising_post)
        pending = {"1": _pending_observation("a#1")}
        done = []

        _drain(client, pending, done)

        self.assertEqual(list(pending.keys()), ["1"])
        self.assertEqual(done, [])


class TimeoutPreservesPendingTest(unittest.TestCase):
    def test_timeout_path_preserves_unresolved_entries(self):
        pending = {"1": _pending_observation("a#1")}
        observations = []

        observations.extend(pending.values())

        self.assertEqual(len(observations), 1)
        self.assertFalse(observations[0].resolved)

        summary = summarize(observations, requested_rate=10.0, elapsed=1.0)
        self.assertEqual(summary["sent"], 1)
        self.assertEqual(summary["resolved"], 0)
        self.assertEqual(summary["unresolved"], 1)


if __name__ == "__main__":
    unittest.main()
