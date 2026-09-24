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
