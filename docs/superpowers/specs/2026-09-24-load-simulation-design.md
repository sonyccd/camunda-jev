# Load simulation for the Jev Router connector

Date: 2026-09-24
Status: approved design, not yet implemented

## Why

The connector works, but nobody knows how it behaves in bulk. Two questions are open:

1. **Does it hold up under load?** Throughput and latency when tickets arrive at a steady rate.
2. **How often does it hand work to a human?** The `undecided` fallback is the whole point of the
   design, and its rate is currently unknown.

A third question matters more than either, and cannot be answered by watching production: **is the
threshold set correctly?** A run of unlabelled tickets tells you how often Jev escalated, but not
whether those escalations were right, nor whether the confident routes went to the correct queue.
This design therefore feeds *labelled* tickets, which turns the exercise from an observation into a
measurement.

## Goals

- Drive a few thousand synthetic tickets through a real Camunda cluster at a controlled rate.
- Measure throughput, latency, and escalation rate.
- Measure correctness: misroutes, warranted vs wasted escalations.
- Compute what a different threshold *would* have produced, offline, from a single run.

## Non-goals

- Benchmarking Camunda itself. The cluster is a dependency, not the subject.
- Tuning Jev prompts or criteria. The wording under test is whatever the process uses.
- Load testing the Jev API's limits. Feed rate stays well inside the documented 1,200 req/min.
- Any change to `JevRouterFunction` or the connector's behaviour.

## Architecture

Five pieces, each independently testable:

```
corpus.json ──> generator.py ──> loadtest.py ──> Camunda REST ──> support-ticket-loadtest.bpmn
   (data)        (sampling)       (feed+poll)                             │
                                       │                                  v
                                       └──────> report.py ──> summary + CSV
```

`generator.py` and `report.py` are pure functions over data — no network, no clock — so the parts
most likely to be silently wrong are the parts easiest to unit test. `loadtest.py` owns all I/O.

**No third-party dependencies.** Standard library only, including `unittest` rather than pytest, so
CI needs no `pip install` step and the harness runs anywhere Python 3.9+ exists.

## The load-test process

New file `examples/support-ticket-loadtest.bpmn`, process id `support-ticket-loadtest`. The existing
`support-ticket-routing.bpmn` is not modified.

```
Start  (ticket, expected, caseId, threshold supplied at creation)
  -> Route ticket   [ai.typesafe:jev-router:1]
  -> Gateway
       choice = billing    -> End "Routed to billing"
       choice = technical  -> End "Routed to technical"
       choice = sales      -> End "Routed to sales"
       choice = undecided  -> Human review [user task]  -> (stays open)
```

Two deliberate differences from the demo process:

- **Confident routes end immediately** rather than parking in a queue user task. Instances complete,
  so throughput is a real number rather than an artefact of nobody working the queue.
- **Undecided parks at a user task and stays there.** Escalations accumulate visibly in Tasklist and
  can be counted at the end, which is the behaviour under study.

Input mappings mirror the demo process, except `threshold` reads `=threshold` from a process
variable so it can be set per run. The process defines **no default** for it: the feeder always
supplies it, and an instance started without it should fail loudly rather than silently route at
some unstated threshold.

### Ground truth must not leak

`expected` is a process variable, but the router's ioMapping maps only `=ticket` to `state`. Jev
never sees the label. **A test asserts this**, because a leak would silently invalidate every
accuracy number in the report while leaving it looking plausible.

## Corpus

`simulation/corpus.json`:

```json
{
  "version": 1,
  "cases": [
    {
      "id": "billing-014",
      "expected": "billing",
      "subject": "Charged twice for March",
      "body": "Hi - I've just noticed we were billed twice..."
    }
  ]
}
```

`expected` is one of `billing`, `technical`, `sales`, `ambiguous`.

Roughly 120 hand-authored cases: about 34 per clear team, and ~18 ambiguous. Written to vary in
tone, length, literacy, and structure — terse one-liners, rambling paragraphs, typos, mixed
greetings and sign-offs, and mails raising two issues at once. The ambiguous set is genuinely
ambiguous (a billing question that is really a bug report; an upgrade request that is really a
support problem), not merely short.

The corpus is committed and readable so its fairness can be judged. Overly templated text would
inflate Jev's apparent accuracy; hand-authoring is the mitigation, and reviewability is how that
mitigation gets checked.

## Generator

`simulation/generator.py` — `generate(corpus, count, seed) -> list[Case]`.

Samples with replacement to reach `count`, applying light deterministic perturbation (greeting and
sign-off variants, occasional typo, whitespace changes) so repeated draws are not byte-identical.
Perturbation never changes which team a mail belongs to.

Seeded from `--seed`; the same seed yields the same run. `caseId` is `<corpusId>#<n>`, unique per
generated instance.

## Feeder and collector

`simulation/loadtest.py`.

**Feeder** — a token-bucket limiter at `--rate` instances/sec. For each case:
`POST /v2/process-instances` with `processDefinitionId` and variables `ticket` (`{subject, body}`),
`expected`, `caseId`, `threshold`. Records `sent_at` and the returned `processInstanceKey`.

**Collector** — a background thread polling `POST /v2/variables/search`, filtering
`{"name":"jevResult","processInstanceKey":{"$in":[...]}}` in chunks of 100 over still-pending keys.
Both the `name` filter and `$in` batching were verified against a live 8.9 cluster before this
design was written. Records `observed_at` and the parsed `jevResult`.

Polling every `--poll-interval` seconds (default 2). Pending keys drain as they resolve. The run
ends when all resolve or `--timeout` elapses; unresolved cases are reported as such rather than
silently dropped.

Reading `jevResult` catches both outcomes: a confident route sets it and then completes, an
undecided one sets it and parks at the user task. Polling for *completed instances* would miss every
escalation, which is the opposite of what we want.

## Metrics

Each resolved case is classified by three facts: did it commit (`decided`), was it genuinely
ambiguous (`expected == "ambiguous"`), and was Jev's raw pick right (`jevChoice == expected`).

| decided | ambiguous | jevChoice correct | Outcome | Good? |
|---|---|---|---|---|
| yes | no | yes | `correct_route` | yes |
| yes | no | no | `misroute` | **worst case** |
| yes | yes | — | `overconfident` | no |
| no | yes | — | `warranted_escalation` | yes |
| no | no | yes | `wasted_escalation` | costly but safe |
| no | no | no | `saved_escalation` | escalation prevented a misroute |

This taxonomy is the point of labelling. Without it, `wasted_escalation` and `saved_escalation` are
indistinguishable, yet one is a cost and the other is the feature working.

Reported:

- **Throughput** — requested vs achieved instances/sec; resolutions/sec.
- **Latency** — p50/p95/p99 of `observed_at - sent_at`. This includes poll delay, so the poll
  interval is printed alongside as the error bound. It is not a measure of connector latency alone
  and must not be quoted as one.
- **Escalation rate** — `undecided / resolved`.
- **Misroute rate** — `misroute / resolved`.
- **Outcome table** — counts and percentages for all six classes.
- **Confusion matrix** — `expected` against `jevChoice`, over all resolved cases.
- **Threshold sweep** — see below.
- **Unresolved** — count and reason, never hidden.

### Threshold sweep

Because the connector stores `jevChoice` and `confidence` even when it returns `undecided`, every
case carries what Jev *would* have said at any threshold. For `t` in 0.50…0.95 step 0.05, recompute
`decided = confidence >= t`, re-derive the taxonomy, and print misroute rate and escalation rate at
each `t`.

This is the number the README tells you to tune and cannot otherwise obtain. One run answers it for
the whole range; no re-running at different thresholds, and no extra API spend.

The report prints the table, plus the `t` with the lowest misroute rate and the `t` with the lowest
total error, defined as `misroute + wasted_escalation`.

`overconfident` is deliberately **excluded** from total error. For a genuinely ambiguous ticket
there is no correct team, so committing to one cannot be called wrong on the evidence available —
only unhelpful. Counting it as an error would bias the sweep toward higher thresholds for reasons
the data does not support.

The report does **not** declare an optimum. The trade-off between a misroute and an escalation is a
business cost, not a statistical one.

### CSV

`--out results.csv`, one row per case:

`caseId, corpusId, expected, subject, sent_at, observed_at, latency_ms, choice, decided, jevChoice,
confidence, p_billing, p_technical, p_sales, outcome`

## CLI

```
./jev simulate [--count 2000] [--rate 10] [--threshold 0.7] [--seed 1]
               [--poll-interval 2] [--timeout 300] [--out results.csv]
```

`./jev simulate` wraps `python3 simulation/loadtest.py`, reusing the script's existing `$REST`
endpoint and its check that a cluster is reachable. Deploys `support-ticket-loadtest.bpmn` first if
absent.

## Testing

`simulation/tests/`, standard-library `unittest`:

- **Corpus validity** — every `expected` in range, ids unique, no empty bodies, ambiguous slice
  within an expected band.
- **Generator determinism** — same seed yields identical output; different seeds differ;
  perturbation never changes `expected`.
- **Metrics maths** — the six-way taxonomy against hand-built fixtures covering every row of the
  table; the sweep against a fixture whose answer is known by construction. This is the highest-risk
  code: a misclassification here produces a confident, wrong recommendation.
- **BPMN contract** — a Java test in `ArtifactsTest` asserting the load-test process exists, its four
  gateway conditions, and **that the router's ioMapping does not reference `expected`**.

CI runs the Python unit tests and the BPMN assertions. CI does **not** run the simulation itself,
which needs a live cluster and an API key.

## Cost and limits

2,000 cases is roughly 700K input tokens, about **$0.03** at $0.042/Mtok (output tokens are free).
At the default 10/sec the run issues 600 req/min, half the documented 1,200 req/min limit. Cost is
not a constraint; the rate cap exists to stay a good citizen, not to save money.

## Risks

- **Synthetic mail is not real mail.** Hand-authoring and reviewability reduce this, but results
  should be read as a comparison between thresholds, not an absolute accuracy claim for production.
- **Latency includes poll delay.** Stated in the output; do not quote it as connector latency.
- **The ambiguous label is a judgement.** Whoever writes the corpus decides what "genuinely
  ambiguous" means, and the warranted/wasted escalation split inherits that judgement.
- **A cluster under-provisioned for the feed rate** will show as latency growth, not errors. The
  report prints achieved vs requested throughput so this is visible rather than confusing.
