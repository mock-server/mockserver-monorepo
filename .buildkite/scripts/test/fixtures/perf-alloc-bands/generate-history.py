#!/usr/bin/env python3
"""Regenerate alloc-history-<from>-<to>.json for perf-alloc-band-test.sh and derive-bands.py.

Input: a directory with one b<N>/ per build holding perf-result.json, perf-microbench.json and
perf-microbench-extra.json, fetched per build with
  bk artifacts list <N> -p mockserver-performance-test        (find each file's id)
  bk artifacts download <ID> --build <N> -p mockserver-performance-test
Usage: generate-history.py <dir> > alloc-history-<from>-<to>.json

Kept: every persisted run (master, valid, baseline-eligible, perf queue, fresh image, not an
arm-only result) in timestamp order, with the alloc_bytes_per_op of every microbench and
microbench_extra benchmark it reports (null when absent). Every run must share one JMH config
(.config.jmh): a config change resets the compare step's microbench baseline, so a history
spanning one would replay windows the real compare never builds. Start after the change instead.
"""
import glob
import json
import os
import sys

FAMILIES = ("microbench", "microbench_extra")


def merge(a, b):
    if isinstance(a, dict) and isinstance(b, dict):
        out = dict(a)
        for k, v in b.items():
            out[k] = merge(a[k], v) if k in a else v
        return out
    return b


def persisted(r):
    return (r.get("branch") == "master" and (r.get("validity") or {}).get("valid") is True
            and r.get("baseline_eligible", True) is not False
            and (r.get("agent") or {}).get("queue", "perf") == "perf"
            and not (r.get("config") or {}).get("image_stale")
            and not r.get("run_arm"))


def load(path):
    runs = []
    for d in glob.glob(os.path.join(path, "b*")):
        result = os.path.join(d, "perf-result.json")
        if not os.path.exists(result):
            continue
        r = json.load(open(result))
        for extra in ("perf-microbench.json", "perf-microbench-extra.json"):
            if os.path.exists(os.path.join(d, extra)):
                r = merge(r, json.load(open(os.path.join(d, extra))))
        r["_build"] = int(os.path.basename(d)[1:])
        runs.append(r)
    runs.sort(key=lambda r: r.get("timestamp_utc", ""))
    return [r for r in runs if persisted(r)]


def main():
    runs = load(sys.argv[1])
    configs = {json.dumps((r.get("config") or {}).get("jmh"), sort_keys=True) for r in runs}
    if len(configs) != 1:
        sys.exit(f"{len(configs)} JMH configs in this history; pass a directory from one config only")
    names = sorted({(f, k) for r in runs for f in FAMILIES for k in (r.get(f) or {})})
    # A benchmark run once (an on-demand one) never builds a window, so it is left out.
    names = [n for n in names if sum(1 for r in runs if ((r.get(n[0]) or {}).get(n[1]) or {}).get("alloc_bytes_per_op") is not None) > 1]
    lines = ["{",
             '  "_comment": ' + json.dumps([
                 "alloc_bytes_per_op of every persisted mockserver-performance-test run, oldest first, one JMH",
                 "config throughout. Written by generate-history.py from the builds' artifacts; do not hand-edit."]) + ",",
             '  "metrics": [']
    lines += ["    " + json.dumps(list(n)) + ("," if i < len(names) - 1 else "") for i, n in enumerate(names)]
    lines += ["  ],", '  "runs": [']
    for i, r in enumerate(runs):
        vals = [((r.get(f) or {}).get(k) or {}).get("alloc_bytes_per_op") for f, k in names]
        lines.append("    " + json.dumps({"build": r["_build"], "alloc": vals}, separators=(", ", ": "))
                     + ("," if i < len(runs) - 1 else ""))
    print("\n".join(lines + ["  ]", "}"]))


if __name__ == "__main__":
    main()
