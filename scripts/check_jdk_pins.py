#!/usr/bin/env python3
"""Fail when a docker/ image's pinned Temurin JDK tarball is not Adoptium's latest GA build, or its
major is out of support (neither an available LTS release nor the most recent feature release).

Dependabot cannot see the tarballs the jdk-download stages pin, so this is their staleness alarm,
run daily by .github/workflows/jdk-pin-freshness.yml. Every Dockerfile under docker/ with a
jdk-download stage must pin an x86_64 and an aarch64 tarball; each pin must equal the URL and
sha256 that api.adoptium.net reports as the latest GA JDK for that major and arch. Bump a stale pin
by hand: docs/infrastructure/docker.md#jdk-and-runtime.

    python3 scripts/check_jdk_pins.py [--repo <root>] [--api-dir <dir>]

--api-dir reads <major>-<arch>.json and available_releases.json from <dir> instead of the API
(fixture tests). Exit 0 when every pin is current, 1 when any pin is behind, out of support,
or unreadable, 2 on a usage error.
"""
import argparse
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = ('https://api.adoptium.net/v3/assets/latest/{major}/hotspot'
       '?os=linux&architecture={arch}&image_type=jdk&vendor=eclipse')
RELEASES_API = 'https://api.adoptium.net/v3/info/available_releases'
ARCHES = {'x86_64': 'x64', 'aarch64|arm64': 'aarch64'}
ARM = re.compile(r'(x86_64|aarch64\|arm64)\) STAGE_ARCH=[a-z0-9]+; +JDK_URL=(\S+?); +JDK_SHA256=([0-9a-f]{64}) ;;')
MAJOR = re.compile(r'^https://github\.com/adoptium/temurin(\d+)-binaries/releases/download/([^/]+)/[^/]+\.tar\.gz$')


def logical_lines(text):
    """Joins backslash continuations, as Docker does, so one RUN instruction is one line."""
    out, buf = [], ''
    for line in text.splitlines():
        if line.rstrip().endswith('\\'):
            buf += line.rstrip()[:-1] + ' '
        else:
            out.append(buf + line)
            buf = ''
    if buf:
        out.append(buf)
    return out


def pins(dockerfile):
    """Returns ({adoptium arch: (major, release, url, sha256)}, error)."""
    lines = logical_lines(dockerfile.read_text())
    if not any(re.match(r'FROM\s.*\sAS jdk-download\s*$', l) for l in lines):
        return None, None
    runs = [l for l in lines if l.startswith('RUN ') and 'JDK_URL=' in l]
    if len(runs) != 1:
        return None, f'has a jdk-download stage but {len(runs)} RUN instructions setting JDK_URL'
    found = {}
    for arch, url, sha in ARM.findall(runs[0]):
        m = MAJOR.match(url)
        if not m:
            return None, f'pins a {arch} JDK_URL that is not an Adoptium Temurin release tarball: {url}'
        found[ARCHES[arch]] = (m.group(1), urllib.parse.unquote(m.group(2)), url, sha)
    missing = sorted(set(ARCHES.values()) - set(found))
    if missing:
        return None, f'pins no {"/".join(missing)} tarball'
    return found, None


def fetch(url, fixture, api_dir):
    if api_dir:
        return json.loads((pathlib.Path(api_dir) / fixture).read_text())
    # The API refuses Python's default User-Agent with 403; a 4xx is not retried.
    req = urllib.request.Request(url, headers={'User-Agent': 'jdk-pin-freshness-check'})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read().decode())
        except urllib.error.HTTPError as e:
            if e.code < 500 or attempt == 2:
                raise
        except OSError:
            if attempt == 2:
                raise
        time.sleep(10)


def supported_majors(api_dir):
    """Returns (available LTS majors, most recent feature release) from Adoptium."""
    info = fetch(RELEASES_API, 'available_releases.json', api_dir)
    lts, feature = info['available_lts_releases'], info['most_recent_feature_release']
    if not (isinstance(lts, list) and lts and all(type(v) is int for v in lts) and type(feature) is int):
        raise ValueError(f'unexpected available_releases shape: {info!r:.200}')
    return set(lts), feature


def latest(major, arch, api_dir):
    for asset in fetch(API.format(major=major, arch=arch), f'{major}-{arch}.json', api_dir):
        b = asset.get('binary', {})
        if b.get('image_type') == 'jdk' and b.get('os') == 'linux' and b.get('architecture') == arch:
            return asset['release_name'], b['package']['link'], b['package']['checksum']
    raise ValueError(f'no linux/{arch} JDK in the Adoptium response for {major}')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--repo', default=str(pathlib.Path(__file__).resolve().parent.parent))
    ap.add_argument('--api-dir')
    args = ap.parse_args()
    docker = pathlib.Path(args.repo) / 'docker'
    files = sorted([docker / 'Dockerfile'] + list(docker.glob('*/Dockerfile')))
    errors, cache, checked = [], {}, 0
    support = None
    READ_ERRORS = (OSError, ValueError, KeyError, TypeError, AttributeError)
    for f in files:
        if not f.is_file():
            continue
        rel = f.relative_to(args.repo)
        found, err = pins(f)
        if err:
            errors.append(f'{rel} {err}')
            continue
        for major in sorted({m for m, _, _, _ in (found or {}).values()}):
            if support is None:
                try:
                    support = supported_majors(args.api_dir)
                except READ_ERRORS as e:
                    support = e
            if isinstance(support, Exception):
                errors.append(f"{rel}: cannot read Adoptium's supported releases, so cannot tell whether Temurin {major} is still supported: {support}")
            elif int(major) not in support[0] and int(major) != support[1]:
                errors.append(f'{rel} pins Temurin {major}, which is out of support (Adoptium LTS releases: '
                              f'{", ".join(map(str, sorted(support[0])))}; most recent feature release: {support[1]}): '
                              f'move its pins to a supported major')
        for arch, (major, release, url, sha) in sorted((found or {}).items()):
            checked += 1
            key = (major, arch)
            if key not in cache:
                try:
                    cache[key] = latest(major, arch, args.api_dir)
                except READ_ERRORS as e:
                    cache[key] = e
            want = cache[key]
            if isinstance(want, Exception):
                errors.append(f'{rel}: cannot read the latest Temurin {major} {arch} build: {want}')
            elif (url, sha) != want[1:]:
                errors.append(f'{rel} pins Temurin {major} {arch} {release}, but the latest is {want[0]}:'
                              f' JDK_URL={want[1]} JDK_SHA256={want[2]}')
            else:
                print(f'  ok   {rel}: Temurin {major} {arch} {release} is the latest')
    if checked == 0:
        errors.append(f'found no jdk-download pin under {docker}: the check compared nothing')
    for e in errors:
        print(f'FAIL: {e}', file=sys.stderr)
    if errors:
        print('Bump the pins by hand: docs/infrastructure/docker.md#jdk-and-runtime', file=sys.stderr)
        return 1
    print(f'PASSED: all {checked} Temurin JDK pins are the latest Adoptium GA builds of supported majors')
    return 0


if __name__ == '__main__':
    sys.exit(main())
