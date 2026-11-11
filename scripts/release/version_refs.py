#!/usr/bin/env python3
"""Rewrite references to the released MockServer version, keeping marked history.

At release, update-version-references.sh moves every reference to the previous
release in the docs, READMEs and manifests to the new one. A historical statement
("in 8.0.0 it ran on every read") must survive, or the release makes it false.

Every bounded occurrence of the old version (8.0.0 never matches inside 18.0.0 or
8.0.0.1) is BUMPED unless an explicit, reviewable signal keeps it:

  - the line carries `version-ref:keep`
  - the line sits between `version-ref:keep-begin` and `version-ref:keep-end`
  - the file is in KEEP_FILES (wholly historical)
  - it sits inside a KEEP_LINES fragment (for a file that cannot carry a marker)
  - the line is a SHA-pinned `uses:` (its `# vX.Y.Z` names that action's release)

Nothing else keeps a version: no phrase, no guess. HISTORICAL_PATTERNS only drive
`check`, which fails when a phrase that reads as history ("in 8.0.0", "8.0.0 and
earlier") covers an unmarked occurrence of the current release, or of a later one
up to the next major (written now, history once that release ships), so a person
decides each case: add `version-ref:keep`, or accept the bump with
`version-ref:bump`. `check` also fails on an unterminated keep block and on a
KEEP_LINES fragment that is not on exactly one line of its file, and on a
bump under .github/workflows/, which the release token cannot push.
See docs/operations/release-process.md (Version references).
"""
import argparse
import os
import re
import subprocess
import sys

EXTENSIONS = ('.html', '.md', '.yaml', '.yml', '.json', '.txt')
SKIP_DIRS = {'node_modules', '.git', 'target', '.tmp'}
# Lockfiles pin third-party versions, some by hash: a third-party version equal to the
# old release must not be rewritten.
SKIP_NAMES = {'changelog.md', 'CHANGELOG.md', 'package-lock.json', 'packages.lock.json',
              'requirements-publish.txt'}
# Bumped precisely (jq) by update-version-references.sh, or third-party ranges.
SKIP_SUFFIXES = ('mockserver-node/package.json', 'mockserver-client-node/package.json',
                 'mockserver-ui/package.json')

# Wholly historical files.
KEEP_FILES = {
    # startup benchmark baseline: the published 8.0.0 images startup_time.html compares against
    'scripts/perf/startup-variants.json',
    # the plan for the 9.0.0 release itself
    'docs/plans/release-9-follow-ups.md',
    # what changed between releases; listed before it lands, which is harmless
    'jekyll-www.mock-server.com/mock_server/upgrading_to_9.html',
    # a parked plan whose version mentions all record decisions already taken
    'docs/plans/later/security-defaults.md',
}
# Historical lines in files that cannot carry a marker: JSON, plain text read as-is
# by LLMs, plan docs, and CI/agent control files whose edits need separate approval.
# Each entry is a fragment of one line, version included; it keeps the occurrences
# inside it, and `check` fails unless it is found on exactly one line of its file.
KEEP_LINES = {
    '.buildkite/release-preflight-pipeline.yml': ['exact failure that half-published 8.0.0'],
    '.github/dependabot.yml': ['leaving 8.0.0 without a VS Code release'],
    '.github/workflows/dependabot-auto-merge.yml': ['a few hours before 8.0.0 was cut'],
    '.opencode/skills/renew-test-certs/SKILL.md': ['(before 9.0.0,'],
    '.opencode/skills/release-management/SKILL.md': [
        'MockServer 8.0.0 half-published because',
        'refused it (the 8.0.0 npm case)',
    ],
    'docs/plans/performance-measurement-backlog.md': [
        '9.0.0 has shipped (2026-10-15)',
        '9.0.0 has shipped, so this is now',
    ],
    'jekyll-www.mock-server.com/llms-full.txt': [
        'Changed in 9.0.0 (breaking): 8.0.0 forwarded each message',
        'to log every comparison (the default before 9.0.0)',
    ],
    'mockserver-performance-test/perf-budgets.json': [
        'the class of change the 8.0.0 HTTP/2-multiplex migration',
        'The one-time pre-8.0.0-vs-8.0.0 comparison',
    ],
}

# Any version, with the prefixes prose puts in front of it.
_ANY = r'`?(?:MockServer\s+|mockserver-)?`?v?\d+\.\d+\.(?:\d+|x)(?![\d])`?'
# Phrases that read as history. They never keep anything: `check` fails when one
# covers an unmarked occurrence, so a person marks it or accepts the bump.
HISTORICAL_PATTERNS = [
    r'(?:[Bb]efore|[Uu]ntil|[Ss]ince|[Ff]ixed in|[Rr]emoved in|[Ii]ntroduced in|[Dd]eprecated in'
    r'|[Aa]dded in|[Uu]pdated in|[Rr]eleased in|[Cc]hanged in|[Aa]s of|[Rr]equires?'
    r'|[Mm]inimum version:?|switched [^.]+ in|moved [^.]+ in|migrated [^.]+ in'
    r'|renamed [^.]+ in|published [^.]+ in)\s+' + _ANY,
    r'\b[Ii]n\s+' + _ANY,
    r'\b(?:from|than|after|up to|through|against|inside|vs\.?|leaving|half-published|shipped)\s+' + _ANY,
    _ANY + r"(?:'s\b|\s+(?:and|or)\s+(?:earlier|older|later|newer)\b|-(?:alpha|beta|rc|M)\b)",
    r'\b(?:pre|post)-' + _ANY,
    r'-vs-' + _ANY,
    r'\b[Tt]he\s+' + _ANY + r'\s+(?:[\w/-]+\s+)?(?:release|changelog|migration|case|incident|regression|behaviour|behavior|dashboard|defaults?)\b',
    _ANY + r'\s+(?:logged|raised|hashed|ran|runs|sent|forwarded|shipped|had|was|were|did|made|'
            r'opened|closed|treated|returned|used|still|bundled|half-published)\b',
]
HISTORICAL = [re.compile(p) for p in HISTORICAL_PATTERNS]

KEEP_LINE = re.compile(r'version-ref:\s*keep(?![-\w])')
BUMP_LINE = re.compile(r'version-ref:\s*bump\b')
KEEP_BEGIN = re.compile(r'version-ref:\s*keep-begin\b')
KEEP_END = re.compile(r'version-ref:\s*keep-end\b')
# A SHA-pinned action's trailing `# vX.Y.Z` names that action's release, never MockServer's.
PINNED_ACTION = re.compile(r'\buses:\s*[^\s@]+@[0-9a-f]{40}\b')
# The release pushes with a token that GitHub refuses for any change under this path.
WORKFLOWS = '.github/workflows/'


def bounded(version):
    return re.compile(r'(?<![0-9.])' + re.escape(version) + r'(?![0-9]|\.[0-9])')


def is_candidate(rel):
    parts = rel.split('/')
    name = parts[-1]
    if not name.endswith(EXTENSIONS) or name in SKIP_NAMES:
        return False
    if any(p in SKIP_DIRS for p in parts[:-1]) or '/helm/charts/' in '/' + rel:
        return False
    return not any(rel == s or rel.endswith('/' + s) for s in SKIP_SUFFIXES)


def walk(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for f in sorted(filenames):
            full = os.path.join(dirpath, f)
            rel = os.path.relpath(full, root).replace(os.sep, '/')
            if is_candidate(rel) and not os.path.islink(full):
                yield rel


def tracked(root):
    out = subprocess.run(['git', '-C', root, 'ls-files', '-z'], check=True,
                         stdout=subprocess.PIPE).stdout.decode('utf-8', 'surrogateescape')
    return sorted(r for r in out.split('\0') if r and is_candidate(r)
                  and os.path.isfile(os.path.join(root, r)))


def classify(rel, lines, versions):
    """Yield (line_no, start, end, version, verdict), verdict one of keep, bump and
    suspect (a bump that a historical phrase covers, with no bump marker); then
    (line_no, -1, -1, None, 'unterminated') for a keep-begin with no keep-end."""
    finders = [(v, bounded(v)) for v in versions]
    fragments = KEEP_LINES.get(rel, ())
    in_block, block_start = False, 0
    for n, line in enumerate(lines, 1):
        if KEEP_BEGIN.search(line) and not in_block:
            in_block, block_start = True, n
        hits = [(m.start(), m.end(), v) for v, rx in finders for m in rx.finditer(line)]
        if hits:
            keep = rel in KEEP_FILES or in_block or KEEP_LINE.search(line) or PINNED_ACTION.search(line)
            # A KEEP_LINES fragment keeps only the occurrences inside it.
            fragment_spans = [(m.start(), m.end()) for f in fragments for m in re.finditer(re.escape(f), line)]
            accepted = BUMP_LINE.search(line)
            history = [] if keep or accepted else [m.span() for rx in HISTORICAL for m in rx.finditer(line)]
            for start, end, v in sorted(hits):
                if keep or any(a <= start and end <= b for a, b in fragment_spans):
                    verdict = 'keep'
                elif any(a <= start and end <= b for a, b in history):
                    verdict = 'suspect'
                else:
                    verdict = 'bump'
                yield n, start, end, v, verdict
        if KEEP_END.search(line):
            in_block = False
    if in_block:
        yield block_start, -1, -1, None, 'unterminated'


def read_lines(path):
    with open(path, encoding='utf-8', errors='surrogateescape', newline='') as fh:
        return fh.read().splitlines(keepends=True)


def pairs(args):
    result = [(args.old, args.new)]
    if args.old_api and args.new_api and args.old_api != args.new_api:
        result.append((args.old_api, args.new_api))
    return result


def cmd_rewrite(args):
    mapping = dict(pairs(args))
    warnings = 0
    for rel in walk(args.root):
        path = os.path.join(args.root, rel)
        lines = read_lines(path)
        edits = {}
        for n, start, end, v, verdict in classify(rel, lines, list(mapping)):
            if verdict in ('bump', 'suspect'):
                edits.setdefault(n, []).append((start, end, mapping[v]))
            if verdict in ('suspect', 'unterminated'):
                warnings += 1
                print(f'warning: {verdict}: {rel}:{n}: {describe(lines, n, start, verdict)}', file=sys.stderr)
        if not edits:
            continue
        for n, spans in edits.items():
            line = lines[n - 1]
            for start, end, new in sorted(spans, reverse=True):
                line = line[:start] + new + line[end:]
            lines[n - 1] = line
        with open(path, 'w', encoding='utf-8', errors='surrogateescape', newline='') as fh:
            fh.write(''.join(lines))
        print(rel)
    if warnings:
        print(f'warning: {warnings} reference(s) bumped although they read as history, or an unterminated '
              f'keep block; run scripts/release/version_refs.py check', file=sys.stderr)
    return 0


def describe(lines, n, start, verdict):
    if verdict == 'unterminated':
        return 'version-ref:keep-begin has no version-ref:keep-end; everything after it is kept'
    text = lines[n - 1].rstrip('\r\n')
    return text[max(0, start - 70):start + 40].strip()


def stale_allowlist(root):
    """Allowlist entries that no longer match. A deleted file is not a problem: its
    entries simply have nothing left to keep."""
    problems = []
    for rel, fragments in sorted(KEEP_LINES.items()):
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            continue
        lines = read_lines(path)
        for fragment in fragments:
            found = sum(fragment in line for line in lines)
            if found != 1:
                problems.append(f'{rel}: KEEP_LINES fragment is on {found} lines, not 1: {fragment}')
    return problems


def config_versions(root):
    found = {}
    with open(os.path.join(root, 'jekyll-www.mock-server.com/_config.yml'), encoding='utf-8') as fh:
        for line in fh:
            m = re.match(r'^(mockserver_version|mockserver_api_version):\s*(\S+)', line)
            if m:
                found[m.group(1)] = m.group(2)
    return found['mockserver_version'], found['mockserver_api_version']


_VERSION_TOKEN = re.compile(r'(?<![0-9.])(\d+)\.(\d+)\.(\d+|x)(?![0-9]|\.[0-9])')


def upcoming_versions(lines, version):
    """Versions after `version`, up to the next major: those mentions are about a
    release not yet out, and become history once it ships, so they are checked too."""
    major, minor, patch = (int(p) for p in version.split('.'))
    found = set()
    for line in lines:
        for m in _VERSION_TOKEN.finditer(line):
            x, y, z = int(m.group(1)), int(m.group(2)), m.group(3)
            if x > major + 1:
                continue
            if z == 'x':
                newer = (x, y) > (major, minor)
            else:
                newer = (x, y, int(z)) > (major, minor, patch)
            if newer:
                found.add(m.group(0))
    return found


def cmd_check(args):
    version, api = (args.version, args.api_version) if args.version else config_versions(args.root)
    in_git = os.path.exists(os.path.join(args.root, '.git'))
    files = tracked(args.root) if in_git else list(walk(args.root))
    bad = 0
    for rel in files:
        lines = read_lines(os.path.join(args.root, rel))
        versions = [v for v in (version, api) if v] + sorted(upcoming_versions(lines, version))
        for n, start, end, v, verdict in classify(rel, lines, versions):
            if args.list:
                print(f'{verdict}\t{rel}:{n}\t{describe(lines, n, start, verdict)}')
            if verdict in ('suspect', 'unterminated'):
                bad += 1
                if not args.list:
                    print(f'{rel}:{n}: {verdict}: {describe(lines, n, start, verdict)}')
            elif verdict == 'bump' and rel.startswith(WORKFLOWS) and v in (version, api):
                bad += 1
                if not args.list:
                    print(f'{rel}:{n}: workflow: the release cannot push a bump here (its token has no '
                          f'workflow scope); add `version-ref:keep` or remove the version: '
                          f'{describe(lines, n, start, verdict)}')
    if in_git or args.allowlist:
        for problem in stale_allowlist(args.root):
            bad += 1
            print(problem)
    if bad and not args.list:
        print(f'\n{bad} problem(s) with references to {version} or later. A "suspect" line reads as '
              f'history but the release will bump it: add `version-ref:keep` to keep it (or list the exact '
              f'line in KEEP_LINES when the file cannot carry a marker), or `version-ref:bump` to accept '
              f'the bump. See docs/operations/release-process.md.', file=sys.stderr)
    return 1 if bad and not args.warn_only else 0


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='cmd', required=True)
    r = sub.add_parser('rewrite', help='bump every unkept reference in place; print changed files')
    r.add_argument('root')
    r.add_argument('--old', required=True)
    r.add_argument('--new', required=True)
    r.add_argument('--old-api')
    r.add_argument('--new-api')
    c = sub.add_parser('check', help='fail on unmarked history, unterminated blocks and stale allowlists')
    c.add_argument('root')
    c.add_argument('--version', help='default: mockserver_version in the Jekyll _config.yml')
    c.add_argument('--api-version', help='default: mockserver_api_version in the Jekyll _config.yml')
    c.add_argument('--list', action='store_true', help='print every occurrence with its verdict')
    c.add_argument('--warn-only', action='store_true', help='report but exit 0')
    c.add_argument('--allowlist', action='store_true', help='check KEEP_FILES/KEEP_LINES outside git too')
    args = p.parse_args()
    sys.exit(cmd_rewrite(args) if args.cmd == 'rewrite' else cmd_check(args))


if __name__ == '__main__':
    main()
