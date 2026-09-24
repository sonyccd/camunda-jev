# Jev Router Connector for Camunda 8

A Camunda 8 outbound connector that routes work by *meaning*: you give it some data, a question and
a list of options, and the TypeSafe [Jev](https://docs.typesafe.ai) model picks one. When Jev isn't
confident enough it returns `undecided` instead of guessing, so a standard exclusive gateway can
send uncertain work to a human.

## How it works

The connector asks Jev exactly one **Choice** question per service task. The answer comes back as a
typed pick plus a confidence score, which the connector compares against your threshold:

| Situation | `choice` | `decided` |
|---|---|---|
| `confidence >= threshold` | Jev's pick | `true` |
| `confidence < threshold` | `undecided` | `false` |
| API failure | *(throws)* | — |

That third row is deliberate. Network errors, timeouts, non-2xx responses and malformed JSON all
throw a connector exception, which becomes a Camunda incident through the normal job-retry
machinery. **Failures are never routed to `undecided`** — "the model wasn't sure" and "the call
broke" demand different responses, and collapsing them would hide outages behind a business path.

## Quick start

Everything runs through one script. From a fresh clone:

```bash
./jev key       # prompts for your TypeSafe API key, input hidden
./jev demo
```

`./jev key` writes `.env` with `600` permissions. Use it rather than typing the key into a shell
command — anything passed as an argument is recorded in your shell history in plaintext.

That builds the connector, starts Camunda 8 and the Connector Runtime in Docker, deploys the
example process, pushes a support ticket through it, and prints where Jev routed it:

```
  choice        technical
  decided       True
  confidence    0.82
  jev picked    technical
  model         jev-1.13.0
  probabilities
    technical     0.85  #########################
    billing       0.08  ##
    sales         0.07  ##
```

### Commands

| Command | What it does |
|---|---|
| `./jev demo [text]` | The whole thing: build, start, deploy, route a ticket |
| `./jev key` | Store your API key in `.env` via a hidden prompt |
| `./jev doctor` | Check prerequisites and the API key |
| `./jev test` | Run the test suite — offline, no key needed |
| `./jev ask "<text>"` | Route one piece of text. **No Docker**, ~5 seconds |
| `./jev smoke` | Verify the live API contract through `JevClient` |
| `./jev up` / `./jev down` | Start / stop the local stack (`down --clean` wipes volumes) |
| `./jev deploy` | Deploy the example process |
| `./jev run [text]` | Start one ticket and show where it routed |
| `./jev status` / `./jev logs` | Container and endpoint health / tail logs |
| `./jev simulate [opts]` | Feed N labelled tickets through the load-test process and report |

`./jev ask` is the fastest way to see the model work — it needs only a key, and it drives the same
`JevRouterFunction` the runtime executes, so it is not a separate code path.

Requirements: Docker, Maven, and a JDK 21+. `./jev doctor` tells you what is missing. The demo stack
is ~2.5GB of images on first pull and needs about 3GB of RAM.

### What the stack runs

`docker/docker-compose.yml` brings up Camunda 8.9 (Zeebe + Operate + Tasklist, H2-backed — no
Elasticsearch) plus the connectors bundle with this connector's jar mounted at `/opt/custom`, which
is the bundle's documented drop-in path. Operate is at `localhost:8080` (`demo` / `demo`).

The compose file passes your key as **`SECRET_TYPESAFE_API_KEY`**, not `TYPESAFE_API_KEY`. The
Connector Runtime only exposes environment variables prefixed `SECRET_` as connector secrets, which
is what lets `{{secrets.TYPESAFE_API_KEY}}` resolve. Getting this wrong fails at job execution with
`Secret with name 'TYPESAFE_API_KEY' is not available`, not at startup.

### Using it in your own cluster

Add the key as a secret (Camunda SaaS: Cluster → Connector secrets; Self-Managed: a `SECRET_`-prefixed
environment variable on the runtime), run `mvn clean verify`, and mount the resulting
`target/jev-router-connector-0.1.0-SNAPSHOT.jar` into your Connector Runtime at `/opt/custom`. Then
import `element-templates/jev-router-connector.json` into Modeler.

> The element template is **generated** from the annotations in `JevRequest.java` and
> `JevRouterFunction.java` during the build. Never hand-edit it — your changes will be overwritten.
> Change the annotations instead.

## Fields

| Field | Type | Required | Default | Notes |
|---|---|---|---|---|
| API key | String | Yes | `{{secrets.TYPESAFE_API_KEY}}` | Use a secret reference, never a raw key |
| State | FEEL / String | Yes | — | The data Jev judges. A FEEL map or list is sent as structured JSON |
| Question | String | Yes | — | Plain-language instruction |
| Options | FEEL map | Yes | — | Option name → description. 2–255 options |
| Confidence threshold | Number | Yes | `0.7` | How sure Jev must be before it commits |
| Model | String | No | `jev-1.13.0` | In the collapsed **Advanced** group |
| Result variable | String | Yes | `jevResult` | Standard connector output mapping |

`undecided` is reserved and rejected as an option name, so that "Jev picked it" stays
distinguishable from "we fell below the threshold".

Option descriptions can be plain strings or richer nested structures, which the API also accepts:

```
={
  billing: "Payments, invoicing, refunds",
  technical: {
    what: "Bugs, outages, integrations",
    not_for: "Questions about pricing",
    examples: ["the API returns 500", "webhooks stopped firing"]
  },
  sales: "Pricing, upgrades, new accounts"
}
```

## Output

```json
{
  "choice": "technical",
  "decided": true,
  "confidence": 0.82,
  "jevChoice": "technical",
  "probabilities": { "billing": 0.08, "technical": 0.85, "sales": 0.07 },
  "model": "jev-1.13.0"
}
```

`jevChoice` keeps Jev's raw pick even when the result is `undecided` — that's what makes threshold
tuning possible. Gateway conditions look like:

```
=jevResult.choice = "billing"
=jevResult.choice = "undecided"
```

## Setting the threshold

**Confidence is not the winning option's probability.** It measures how concentrated the whole
distribution is, so a pick can carry a high probability and still report low confidence. The
TypeSafe docs' own example: a choice at `0.61` probability has a confidence of just `0.42`, because
a second option took `0.35`. Low confidence usually means *no option clearly won*, not that the top
option is wrong.

Start high — `0.8`–`0.9` — so most work goes to a human at first. Log `jevChoice` alongside where
the work actually ended up, then compare: if humans keep agreeing with `jevChoice` on items that
came back `undecided`, lower the threshold. If they keep overriding confident picks, raise it.

Two caveats worth planning around. Jev's probabilities can vary between requests, so tune on a body
of real data rather than a handful of replays. And the right threshold depends on consequences, not
on the model — a decision that's cheap to undo can run much looser than one that isn't.

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

## Limits

- **Token budget**: 64k tokens per request total, and 32k for the state plus the longest question.
- **Options**: 2–255 per question.
- **Input**: text and JSON only. No images, no documents.
- **No explanation.** Jev returns a pick, probabilities and a confidence — never a reason. If you
  need to justify a decision to an auditor or a customer, capture `probabilities` and the input
  state yourself.
- **One question per task.** Noul and Score primitives, and multi-question calls, are out of scope
  for v1.

## Errors

| Code | Cause |
|---|---|
| `INVALID_INPUT` | Validation failed before any call was made |
| `JEV_TIMEOUT` | No response within 10s |
| `JEV_CONNECTION_ERROR` | The API was unreachable |
| `JEV_API_ERROR` | Non-2xx response; the message carries the API's own detail and the request id |
| `JEV_MALFORMED_RESPONSE` | Unparseable body, missing answer, or a pick outside the supplied options |

The connector does not retry internally — Camunda's job retries and incidents own that. For
`429`/`529` in particular, set a **Retry backoff** on the task rather than leaving it at `PT0S`.

The API key is never logged. State is logged only as its type and length, never its content.

## Deviations from the original spec

The spec asked that anything differing from upstream conventions or the live API be recorded here.

1. **Object state is sent as native JSON**, not serialized to a string. The API accepts
   `string | object | array` for `state`, and the docs recommend structured data for records and
   chat logs.
2. **Option descriptions may be nested objects or null**, not only strings — also per the API.
3. **The element template is generated, not hand-written.** The upstream template does this and its
   conventions win.
4. **The project has no parent POM.** `camunda/connector-template-outbound` is standalone and
   declares the SDK as `provided`; it no longer inherits `connector-parent`.
5. **The Classic Function API is used** (`OutboundConnectorFunction`) although the upstream template
   now leads with the annotation-based Operations API. A single-purpose connector would otherwise
   show analysts an "Operation" dropdown with exactly one entry.
6. **SDK pinned to 8.9.12** rather than the template's 8.9.5 — the current GA of that line. The SDK's
   minor version must match the Connector Runtime's minor version; patches are freely swappable.
7. **`model` is always sent.** The spec treats it as optional, but it is required by the HTTP API —
   the `jev-latest` default exists only in TypeSafe's own SDKs.
8. **The state budget is two limits, not one** (64k/32k), where the spec said "~32K".
9. Errors are reported with distinct codes (above) rather than a single generic one, so incidents
   can be triaged without opening the stack trace.

## Development

```bash
./jev test           # compile, run all tests, regenerate the element template
./jev smoke          # the live tests, which do hit the real API (needs a key)
```

The HTTP client is injected into `JevClient`, so the normal suite mocks it — **no test in
`mvn verify` makes a live API call**. The live tests in `LiveJevTest` are tagged `live` and excluded
from the default build; `./jev smoke` opts into them. They cost tokens.

`./jev` wraps Maven, so `mvn clean verify` works directly too.
