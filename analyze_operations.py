#!/usr/bin/env python3
"""Analyze operation durations from Octopus server task log."""

import re
from collections import defaultdict

LOG_FILE = "/var/home/matthewcasperson/Downloads/ServerTasks-3114824.log.txt"

# Pattern: "Operation Cached <type> result for <OPERATION> took <DURATION> ms"
PATTERN = re.compile(
    r"WARNING: Operation Cached \w+ result for (.+?) took (\d+) ms"
)

durations = defaultdict(list)

with open(LOG_FILE, "r") as f:
    for line in f:
        m = PATTERN.search(line)
        if m:
            operation = m.group(1)
            ms = int(m.group(2))
            durations[operation].append(ms)

print(f"{'Operation':<60} {'Count':>6} {'Avg (min)':>10} {'Min (min)':>10} {'Max (min)':>10}")
print("-" * 100)

for op in sorted(durations, key=lambda k: sum(durations[k]) / len(durations[k]), reverse=True):
    times = durations[op]
    avg = sum(times) / len(times) / 60000
    mn = min(times) / 60000
    mx = max(times) / 60000
    print(f"{op:<60} {len(times):>6} {avg:>10.2f} {mn:>10.2f} {mx:>10.2f}")

print("-" * 100)
print(f"Total unique operations: {len(durations)}")
print(f"Total warnings: {sum(len(v) for v in durations.values())}")

