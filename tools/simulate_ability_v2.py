#!/usr/bin/env python3
"""Reproduce model-internal ability-update comparisons using only Python stdlib.

This is a synthetic sanity check, not validation against learners. Outcomes follow
the same assumed Rasch curve used by the updater. Integer word bands, 70/30 nearby
sampling, and 8% upward probes approximate the learning stream before v2.0.2.
Words are always independent; calibration and repeated reviews are excluded.

Run: python3 tools/simulate_ability_v2.py --output /tmp/ability-simulation.json
"""

import argparse
import json
import math
import random
import statistics
from pathlib import Path


def probability(level, difficulty):
    return 1.0 / (1.0 + math.exp(-1.25 * (level - difficulty) - math.log(.7 / .3)))


def next_level(level, difficulty, known, history, algorithm):
    expected = probability(level, difficulty)
    if algorithm == "old":
        gain = .02 * (1.0 + abs(difficulty - level) * .15)
    else:
        recent = history[-20:]
        bias = (sum(outcome - probability(level, band) for band, outcome in recent)
                / len(recent)) if len(recent) >= 8 else 0.0
        acceleration = min(1.0, max(0.0, (abs(bias) - .10) / .20))
        gain = min(.12 * (1.0 + acceleration), .12 / max(expected, 1.0 - expected))
    return min(10.0, max(1.0, level + gain * (known - expected)))


def simulate(start, true_level, seed, algorithm, selection="adaptive", stream="model"):
    rng = random.Random(seed)
    level = start
    history = []
    changes = {}
    for count in range(1, 101):
        if selection == "fixed":
            difficulty = start
        else:
            difficulty = math.floor(level) if count % 10 < 7 else math.ceil(level)
            distant = [band for band in range(1, 11) if 3.0 <= band - level <= 4.0]
            if distant and rng.random() < .08:
                difficulty = rng.choice(distant)
        if stream == "model":
            known = rng.random() < probability(true_level, difficulty)
        elif stream == "known":
            known = True
        elif stream == "unknown":
            known = False
        elif stream == "alternating":
            known = count % 2 == 1
        else:
            known = count % 10 < 7
        level = next_level(level, difficulty, known, history, algorithm)
        history.append((difficulty, known))
        if count in (30, 50, 100):
            changes[count] = level - start
    return changes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runs", type=int, default=1000)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.runs < 2:
        parser.error("--runs must be at least 2")
    rows = []
    for selection in ("fixed", "adaptive"):
        for start in (3, 5, 7):
            for offset in (-2, -1, 0, 1, 2):
                row = {"selection": selection, "starting_level": start,
                       "true_model_level": start + offset}
                for algorithm in ("old", "new"):
                    trials = [simulate(start, start + offset, seed, algorithm, selection)
                              for seed in range(args.runs)]
                    row[algorithm] = {
                        "mean_change": {count: round(statistics.mean(t[count] for t in trials), 4)
                                        for count in (30, 50, 100)},
                        "standard_deviation_at_100": round(statistics.stdev(t[100] for t in trials), 4),
                    }
                rows.append(row)
    checks = []
    for stream in ("known", "unknown", "alternating", "seventy_percent"):
        for selection in ("fixed", "adaptive"):
            checks.append({"stream": stream, "selection": selection,
                           **{algorithm: simulate(5, 5, 1729, algorithm, selection, stream)
                              for algorithm in ("old", "new")}})
    result = {"notice": "Synthetic model-internal validation; not learner evidence or a learning forecast.",
              "runs_per_condition": args.runs, "seeds": [0, args.runs - 1],
              "parameters": {"base_gain": .12, "max_step": .12, "history_window": 20,
                             "minimum_trend_samples": 8, "bias_deadband": .10,
                             "bias_ramp_width": .20},
              "random_streams": rows, "deterministic_checks": checks}
    encoded = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded)
        print(f"Saved {len(rows)} simulation conditions to {args.output}")
    else:
        print(encoded, end="")


if __name__ == "__main__":
    main()
