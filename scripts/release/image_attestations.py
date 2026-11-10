#!/usr/bin/env python3
"""Fail unless every platform image of a pushed image index carries an SBOM and a provenance attestation.

  image_attestations.py <image-ref>   e.g. mockserver/mockserver:9.3.0

BuildKit (`docker buildx build --sbom=true --provenance=...`) stores attestations in the image
index as one attestation manifest per platform image, whose layers are in-toto statements
annotated with their predicate type. The index and manifests are read from the registry with
`docker buildx imagetools inspect --raw`. Exit 0 when complete, 1 when an attestation is
missing, 2 when the registry cannot be read or returns something unexpected.
"""
import json
import subprocess
import sys

SBOM_PREDICATE = "https://spdx.dev/Document"
PROVENANCE_PREFIX = "https://slsa.dev/provenance/"
ATTESTATION_TYPE = "attestation-manifest"
INDEX_MEDIA_TYPES = (
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
)


class RegistryError(Exception):
    pass


def repository(ref):
    """The repository part of a ref: drops a trailing :tag (not a registry :port) and any @digest."""
    name = ref.split("@", 1)[0]
    last = name.rsplit("/", 1)[-1]
    return name.rsplit(":", 1)[0] if ":" in last else name


def missing_attestations(index, fetch_manifest):
    """Problems with the index's attestations; empty when every platform image has both kinds.

    fetch_manifest(digest) returns the parsed manifest with that digest from the same repository.
    """
    if index.get("mediaType") not in INDEX_MEDIA_TYPES:
        return [f"not a multi-platform image index (mediaType {index.get('mediaType')!r})"]
    manifests = index.get("manifests") or []
    attestations = {}
    images = []
    for m in manifests:
        annotations = m.get("annotations") or {}
        if annotations.get("vnd.docker.reference.type") == ATTESTATION_TYPE:
            attestations[annotations.get("vnd.docker.reference.digest")] = m["digest"]
        else:
            images.append(m)
    if not images:
        return ["the index lists no platform images"]
    problems = []
    for image in images:
        platform = image.get("platform") or {}
        label = "/".join(p for p in (platform.get("os"), platform.get("architecture"), platform.get("variant")) if p)
        label = f"{label or 'unknown platform'} ({image['digest']})"
        attestation_digest = attestations.get(image["digest"])
        if attestation_digest is None:
            problems.append(f"{label}: no attestation manifest")
            continue
        predicates = [
            (layer.get("annotations") or {}).get("in-toto.io/predicate-type", "")
            for layer in fetch_manifest(attestation_digest).get("layers") or []
        ]
        if SBOM_PREDICATE not in predicates:
            problems.append(f"{label}: no SBOM attestation ({SBOM_PREDICATE})")
        if not any(p.startswith(PROVENANCE_PREFIX) for p in predicates):
            problems.append(f"{label}: no provenance attestation ({PROVENANCE_PREFIX}*)")
    return problems


def inspect_raw(ref):
    try:
        out = subprocess.run(
            ["docker", "buildx", "imagetools", "inspect", "--raw", ref],
            check=True, capture_output=True, text=True, timeout=120,
        ).stdout
        return json.loads(out)
    except (subprocess.SubprocessError, OSError, ValueError) as e:
        detail = getattr(e, "stderr", "") or e
        raise RegistryError(f"docker buildx imagetools inspect --raw {ref} failed: {detail}") from e


def main(argv):
    if len(argv) != 2:
        print("usage: image_attestations.py <image-ref>", file=sys.stderr)
        return 2
    ref = argv[1]
    repo = repository(ref)
    try:
        problems = missing_attestations(inspect_raw(ref), lambda digest: inspect_raw(f"{repo}@{digest}"))
    except (RegistryError, KeyError, TypeError, AttributeError) as e:
        print(f"ERROR: could not read the attestations of {ref}: {e}", file=sys.stderr)
        return 2
    if problems:
        for problem in problems:
            print(f"ERROR: {ref}: {problem}", file=sys.stderr)
        return 1
    print(f"{ref}: every platform image has an SBOM and a provenance attestation")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
