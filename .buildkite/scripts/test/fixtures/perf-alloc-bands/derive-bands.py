#!/usr/bin/env python3
"""Derive the per-benchmark allocation bands in perf-budgets.json from an alloc history fixture.

Usage: derive-bands.py <alloc-history.json> [--exclude-build N ...]
Prints the `bands` object of each alloc_bytes_per_op budget as JSON on stdout, and one line per
benchmark (its band, or why it is not listed) on stderr. Copy each object into its budget entry.

Each benchmark's windows are rebuilt as perf-test-compare.sh builds them (the 10 runs before the
head, at least 5 with a value). Only windows whose median is within LEVEL_PCT of the benchmark's
level (the median of its last 5 values) count, so a band describes the level it is used at.
Left out as not noise: a head at a new level while its window still spans the step up to it (a
step is 3 runs in a row all more than FLOOR above every one of the 5 runs before them), and a head whose next 2
runs are not recorded yet. A head that returns to a size the runs before it already showed is
noise even when the next runs repeat it: some benchmarks flip between two sizes for runs at a
time. --exclude-build leaves out the heads of a build known to carry a real regression that was
later fixed, so that regression is not learned as noise. From the remaining residuals (head minus window median):

  band = max(FLOOR, K * 1.4826 * MAD or the largest upward residual, rounded up to 3 figures)

The largest residual is there because allocation noise comes in whole object sizes (+8, +12,
+24 B) on a few runs in ten, which a MAD of 0 cannot see. A benchmark is listed only with
MIN_WINDOWS such windows and a band narrower than its budget's min_pct of its level.
"""
import json
import math
import statistics
import sys

WINDOW, MIN_BASELINE = 10, 5
K, FLOOR, LEVEL_PCT, MIN_WINDOWS, MIN_PCT = 4, 1.0, 0.10, 15, 0.05


def above3(x):
    """The smallest 3-significant-figure number strictly above x (x > 0)."""
    q = 10 ** (math.floor(math.log10(x)) - 2)
    return round((math.floor(x / q) + 1) * q, max(0, -int(math.log10(q))))


def mad(v):
    m = statistics.median(v)
    return statistics.median([abs(x - m) for x in v])


def derive(history, exclude=()):
    runs = history["runs"]
    out = {}
    for j, (family, bench) in enumerate(history["metrics"]):
        series = [(i, r["build"], r["alloc"][j]) for i, r in enumerate(runs) if r["alloc"][j] is not None]
        values = [v for _, _, v in series]
        level = statistics.median(values[-5:])
        before = [max(values[max(0, n - 5):n]) if n else None for n in range(len(values))]
        steps = [n for n in range(1, len(values) - 2) if min(values[n:n + 3]) > before[n] + FLOOR]
        residuals = []
        for n, (i, build, head) in enumerate(series):
            window = [r["alloc"][j] for r in runs[max(0, i - WINDOW):i] if r["alloc"][j] is not None]
            if len(window) < MIN_BASELINE or build in exclude:
                continue
            m = statistics.median(window)
            if abs(m - level) > LEVEL_PCT * level:
                continue
            nxt = values[n + 1:n + 4]
            if len(nxt) < 2:
                continue
            if head > m and any(t <= n < t + WINDOW and head > before[t] + FLOOR for t in steps):
                continue
            residuals.append(head - m)
        name = f"{bench}.alloc_bytes_per_op"
        if len(residuals) < MIN_WINDOWS:
            out[(family, name)] = {"why": f"{len(residuals)} windows at its current level, under {MIN_WINDOWS}"}
            continue
        raw = max(K * 1.4826 * mad(residuals), max(residuals))
        band = max(FLOOR, above3(raw)) if raw > 0 else FLOOR
        if band >= MIN_PCT * level:
            out[(family, name)] = {"why": f"band {band:g} is not narrower than {MIN_PCT:g} of its level {level:g}"}
            continue
        out[(family, name)] = {"band": band, "median": float(f"{level:.6g}"), "windows": len(residuals)}
    return out


def main():
    args = sys.argv[1:]
    exclude = {int(args[i + 1]) for i, a in enumerate(args) if a == "--exclude-build"}
    history = json.load(open(args[0]))
    derived = derive(history, exclude)
    bands = {}
    for (family, name), d in derived.items():
        print(f"{family}.{name}: " + (f"band {d['band']:g} at median {d['median']:g} ({d['windows']} windows)"
                                       if "band" in d else f"not listed, {d['why']}"), file=sys.stderr)
        if "band" in d:
            bands.setdefault(family, {})[name] = {"band": tidy(d["band"]), "median": tidy(d["median"])}
    lines = []
    for f, m in sorted(bands.items()):
        rows = [f'      {json.dumps(n)}: {{ "band": {json.dumps(v["band"])}, "median": {json.dumps(v["median"])} }}'
                for n, v in m.items()]
        lines.append(f'  "{f}.*.alloc_bytes_per_op": {{\n    "level_pct": {LEVEL_PCT},\n    "metrics": {{\n'
                     + ",\n".join(rows) + "\n    }\n  }")
    print("{\n" + ",\n".join(lines) + "\n}")


def tidy(x):
    return int(x) if float(x).is_integer() else x


if __name__ == "__main__":
    main()
