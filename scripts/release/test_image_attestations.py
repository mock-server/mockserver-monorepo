#!/usr/bin/env python3
"""Unit tests for image_attestations.py: python3 -I scripts/release/test_image_attestations.py"""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import image_attestations as ia  # noqa: E402

AMD64 = "sha256:" + "a" * 64
ARM64 = "sha256:" + "b" * 64
ATT_AMD64 = "sha256:" + "c" * 64
ATT_ARM64 = "sha256:" + "d" * 64
SBOM = ia.SBOM_PREDICATE
SLSA = "https://slsa.dev/provenance/v0.2"


def index(*manifests):
    return {"mediaType": "application/vnd.oci.image.index.v1+json", "manifests": list(manifests)}


def image(digest, arch):
    return {"digest": digest, "platform": {"os": "linux", "architecture": arch}}


def attestation(digest, subject):
    return {
        "digest": digest,
        "platform": {"os": "unknown", "architecture": "unknown"},
        "annotations": {"vnd.docker.reference.type": "attestation-manifest", "vnd.docker.reference.digest": subject},
    }


def fetcher(predicates_by_digest):
    def fetch(digest):
        return {"layers": [{"annotations": {"in-toto.io/predicate-type": p}} for p in predicates_by_digest[digest]]}
    return fetch


class MissingAttestationsTest(unittest.TestCase):
    def test_both_platforms_with_sbom_and_provenance_pass(self):
        idx = index(image(AMD64, "amd64"), image(ARM64, "arm64"),
                    attestation(ATT_AMD64, AMD64), attestation(ATT_ARM64, ARM64))
        fetch = fetcher({ATT_AMD64: [SBOM, SLSA], ATT_ARM64: [SBOM, "https://slsa.dev/provenance/v1"]})
        self.assertEqual(ia.missing_attestations(idx, fetch), [])

    def test_provenance_only_fails_for_missing_sbom(self):
        idx = index(image(AMD64, "amd64"), attestation(ATT_AMD64, AMD64))
        problems = ia.missing_attestations(idx, fetcher({ATT_AMD64: [SLSA]}))
        self.assertEqual(len(problems), 1)
        self.assertIn("no SBOM attestation", problems[0])

    def test_sbom_only_fails_for_missing_provenance(self):
        idx = index(image(AMD64, "amd64"), attestation(ATT_AMD64, AMD64))
        problems = ia.missing_attestations(idx, fetcher({ATT_AMD64: [SBOM]}))
        self.assertEqual(len(problems), 1)
        self.assertIn("no provenance attestation", problems[0])

    def test_platform_without_attestation_manifest_fails(self):
        idx = index(image(AMD64, "amd64"), image(ARM64, "arm64"), attestation(ATT_AMD64, AMD64))
        problems = ia.missing_attestations(idx, fetcher({ATT_AMD64: [SBOM, SLSA]}))
        self.assertEqual(len(problems), 1)
        self.assertIn("linux/arm64", problems[0])
        self.assertIn("no attestation manifest", problems[0])

    def test_attestation_for_another_image_does_not_count(self):
        idx = index(image(AMD64, "amd64"), attestation(ATT_AMD64, ARM64))
        problems = ia.missing_attestations(idx, fetcher({ATT_AMD64: [SBOM, SLSA]}))
        self.assertEqual(problems, [f"linux/amd64 ({AMD64}): no attestation manifest"])

    def test_single_platform_manifest_is_not_an_index(self):
        problems = ia.missing_attestations({"mediaType": "application/vnd.oci.image.manifest.v1+json"}, None)
        self.assertEqual(len(problems), 1)
        self.assertIn("not a multi-platform image index", problems[0])

    def test_index_of_only_attestations_fails(self):
        problems = ia.missing_attestations(index(attestation(ATT_AMD64, AMD64)), fetcher({}))
        self.assertEqual(problems, ["the index lists no platform images"])


class RepositoryTest(unittest.TestCase):
    def test_tag_and_digest_are_dropped_but_a_registry_port_is_kept(self):
        self.assertEqual(ia.repository("mockserver/mockserver:9.3.0"), "mockserver/mockserver")
        self.assertEqual(ia.repository("public.ecr.aws/x/mockserver:9.3.0-graaljs"), "public.ecr.aws/x/mockserver")
        self.assertEqual(ia.repository("localhost:5000/mockserver:1"), "localhost:5000/mockserver")
        self.assertEqual(ia.repository("localhost:5000/mockserver"), "localhost:5000/mockserver")
        self.assertEqual(ia.repository("ghcr.io/m/m@" + AMD64), "ghcr.io/m/m")


if __name__ == "__main__":
    unittest.main()
