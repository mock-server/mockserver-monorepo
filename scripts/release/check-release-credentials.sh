#!/usr/bin/env bash
# Liveness probe for the publishing credentials a MockServer release needs.
#
# WHY THIS EXISTS
#   The release-readiness check used to confirm each secret was *present*
#   (`describe-secret` returns OK). Presence is not validity: an npm token that
#   was three months stale still "existed", so preparation passed, the release
#   ran, and `npm publish` failed with 401 half-way through — after the version
#   bumps had already been pushed. This script probes each credential the
#   release actually publishes with and reports whether it can genuinely
#   authenticate, so a dead credential is caught *before* the pipeline starts.
#
# WHAT IT DOES / DOES NOT DO
#   - Every probe is READ-ONLY. It calls each registry's identity / whoami /
#     login-token endpoint, imports keys into throwaway keyrings, or performs a
#     local shape check. It NEVER publishes, tags, pushes, or mutates anything —
#     this is safe to run repeatedly before a release. (NuGet's check mints a
#     one-day verification key that nuget.org deletes on use; see probe_nuget.)
#   - It NEVER prints secret material, and never echoes or logs a secret value.
#     Secrets are handed to tools out-of-band — via private temp files (umask
#     077, all under one 0700 working dir), curl `-K` config files, or the
#     environment — never on a command line. The ONE unavoidable exception is
#     `aws sts assume-role --external-id`, where the AWS CLI takes the website
#     role's external id as an argument; that value is a role condition key, not
#     an authentication secret.
#   - All temp material lives under a single private working directory that is
#     scrubbed on EXIT / INT / TERM, so a signing key, its passphrase, the
#     cosign key, the TOTP seed, and bearer-token curl configs never survive an
#     interrupt (Ctrl-C is a normal operator action on a pre-release check).
#
# THREE OUTCOMES, KEPT DISTINCT (this is the whole point):
#   VALID          credential authenticated successfully
#   VALID(SHAPE)   well-formed and usable as far as can be checked, but the
#                  registry has no read-only identity endpoint, so liveness
#                  cannot be fully proven (documented per credential)
#   REJECTED       credential present but the registry refused it (the npm case)
#   MALFORMED      credential present but missing a required field / wrong shape
#   ABSENT         secret does not exist / has no such field
#   INDETERMINATE  the probe could not run (no AWS session, network failure,
#                  tool missing, caller not trusted) — reported as its own
#                  outcome, NEVER as a pass. A check that cannot run is not a
#                  green check.
#
# EXIT CODES
#   0  all REQUIRED credentials VALID or VALID(SHAPE)
#   1  a REQUIRED credential was REJECTED / MALFORMED / ABSENT (real finding)
#   2  a REQUIRED credential was INDETERMINATE (could not be proven; fail-closed)
#   3  precondition failed — no usable AWS session, so nothing could be probed
#   Optional (soft-fail channel) credentials are reported but never change the
#   exit code.
#
# USAGE
#   scripts/release/check-release-credentials.sh [--required-only] [--self-test]
#
#   AWS auth comes from the ambient environment (AWS_PROFILE / SSO / env creds),
#   exactly like the other release scripts. Region defaults to eu-west-2 or
#   $AWS_REGION. This script is CI-agnostic.
#
# DESIGN: see docs/operations/release-principles.md and the sibling scripts in
# this directory; logging/helpers are shared via _lib.sh.

# The probe_* functions below are dispatched indirectly via "$probe" from the
# credential catalogue, so shellcheck cannot see the call sites (SC2329).
# shellcheck disable=SC2329
set -uo pipefail
umask 077

RELEASE_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$RELEASE_LIB_DIR/_lib.sh"
# _lib.sh enables `set -e`; a probe harness expects individual probes to fail,
# so disable errexit and manage every failure explicitly.
set +e

UA="mockserver-release-credential-check/1.0"
CURL_TIMEOUT=25
REQUIRED_ONLY=false
# Repo the release pushes version bumps to and creates the GitHub Release on
# (scripts/release/components/github.sh runs `gh release create` here).
GITHUB_RELEASE_REPO="mock-server/mockserver-monorepo"

for arg in "$@"; do
  case "$arg" in
    --required-only) REQUIRED_ONLY=true ;;
    --self-test)     RUN_SELF_TEST=true ;;
    # Print the header comment block only: from line 2 up to the first
    # non-comment line (the blank line after "# DESIGN:"), so shell plumbing
    # below the header never renders as help even if the header grows.
    -h|--help)       awk 'NR==1{next} /^#/{sub(/^# ?/,"");print;next} {exit}' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) log_error "unknown argument: $arg"; exit 64 ;;
  esac
done

# All temp material (curl configs, key files, passphrase, throwaway GNUPGHOME,
# TOTP seed, Docker Hub body) is created UNDER this one 0700 directory and
# scrubbed by the trap on any exit — including Ctrl-C mid-probe. Because every
# path is inside WORKDIR, the single `rm -rf` cleans up even temp files created
# in a probe's command-substitution subshell that its own inline `rm` never
# reached.
WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/credcheck.XXXXXX")"
chmod 700 "$WORKDIR"
# EXIT scrubs on every normal/error path; INT/TERM scrub AND abort (130), so a
# Ctrl-C stops immediately with nothing left on disk rather than continuing into
# a deleted WORKDIR.
trap 'rm -rf "$WORKDIR"' EXIT
trap 'rm -rf "$WORKDIR"; exit 130' INT TERM

# Create a tracked temp file / dir inside WORKDIR (never in the shared $TMPDIR).
new_tmp()    { mktemp "$WORKDIR/f.XXXXXX"; }
new_tmpdir() { mktemp -d "$WORKDIR/d.XXXXXX"; }

# -----------------------------------------------------------------------------
# Low-level helpers
# -----------------------------------------------------------------------------

# Extract a field from a secret's JSON without echoing anything sensitive.
# Prints the field value (may be empty) to stdout.
secret_field() {
  local json="$1" key="$2"
  printf '%s' "$json" | jq -r --arg k "$key" '.[$k] // empty' 2>/dev/null
}

# HTTP status of a read-only request. Extra args (including a `-K <cfg>` file
# carrying the Authorization header) are appended verbatim. curl's own `-w`
# already prints `000` on a transport failure, so we normalise to that (rather
# than appending a second `000` with `|| echo`, which produced `000000` and
# missed the dedicated `000` branch) and default an empty capture to `000` too,
# so the caller reliably reaches the `000` → INDETERMINATE branch.
http_status() {
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time "$CURL_TIMEOUT" -A "$UA" "$@" 2>/dev/null)"
  printf '%s' "${code:-000}"
}

# Turn an HTTP status into an outcome for a bearer/identity probe.
# Emits "STATUS<TAB>NOTE".
classify_http() {
  local status="$1" valid_note="$2"
  case "$status" in
    2*)      printf 'VALID\t%s\n' "$valid_note" ;;
    401|403) printf 'REJECTED\tauthenticated request refused (HTTP %s)\n' "$status" ;;
    000)     printf 'INDETERMINATE\tcould not reach endpoint (network/DNS)\n' ;;
    *)       printf 'INDETERMINATE\tunexpected HTTP %s from identity endpoint\n' "$status" ;;
  esac
}

# Write an Authorization header into a private curl config file; echo its path.
auth_header_cfg() {
  local header="$1" cfg
  cfg="$(new_tmp)"
  printf 'header = "%s"\n' "$header" > "$cfg"
  printf '%s' "$cfg"
}

# -----------------------------------------------------------------------------
# Secret reader with outcome classification
#   Sets SECRET_JSON (on OK) and SECRET_READ_STATUS to OK|ABSENT|INDETERMINATE.
# -----------------------------------------------------------------------------
SECRET_JSON=""
SECRET_READ_STATUS=""
read_secret() {
  local sid="$1" errf out rc
  SECRET_JSON=""
  local -a args=(--region "$REGION" --secret-id "$sid" --query SecretString --output text)
  [[ -n "${AWS_PROFILE:-}" ]] && args+=(--profile "$AWS_PROFILE")
  errf="$(new_tmp)"
  out="$(aws secretsmanager get-secret-value "${args[@]}" 2>"$errf")"
  rc=$?
  if [[ $rc -eq 0 && -n "$out" ]]; then
    SECRET_JSON="$out"
    SECRET_READ_STATUS="OK"
  elif grep -q 'ResourceNotFoundException' "$errf" 2>/dev/null; then
    SECRET_READ_STATUS="ABSENT"
  else
    # AccessDenied, ExpiredToken, throttling, transport — cannot conclude.
    SECRET_READ_STATUS="INDETERMINATE"
  fi
  rm -f "$errf"
}

# -----------------------------------------------------------------------------
# Per-credential probes.
#   Each receives the secret JSON in $1 and emits "STATUS<TAB>NOTE".
#   Probes must remain read-only and must never echo a field value.
# -----------------------------------------------------------------------------

# Sonatype Central Portal — token pair (username/password) → Bearer base64.
# Read-only: lists this account's deployments (page size 1).
probe_sonatype() {
  local json="$1" user pass bearer cfg status
  user="$(secret_field "$json" username)"; pass="$(secret_field "$json" password)"
  [[ -z "$user" || -z "$pass" ]] && { printf 'MALFORMED\tmissing username/password field\n'; return; }
  bearer="$(printf '%s:%s' "$user" "$pass" | base64 | tr -d '\n')"
  cfg="$(auth_header_cfg "Authorization: Bearer $bearer")"
  status="$(http_status -K "$cfg" 'https://central.sonatype.com/api/v1/publisher/deployments?size=1')"
  rm -f "$cfg"
  classify_http "$status" "Central Portal accepted the publisher token"
}

# npm — automation token → GET /-/whoami (the exact check that failed silently).
probe_npm() {
  local json="$1" token cfg status
  token="$(secret_field "$json" token)"
  [[ -z "$token" ]] && { printf 'MALFORMED\tmissing token field\n'; return; }
  cfg="$(auth_header_cfg "Authorization: Bearer $token")"
  status="$(http_status -K "$cfg" 'https://registry.npmjs.org/-/whoami')"
  rm -f "$cfg"
  classify_http "$status" "registry.npmjs.org identified the token"
}

# Build a curl config carrying the GitHub bearer + API Accept header; echo path.
github_cfg() {
  local token="$1" cfg
  cfg="$(new_tmp)"
  { printf 'header = "Authorization: Bearer %s"\n' "$token"
    printf 'header = "Accept: application/vnd.github+json"\n'; } > "$cfg"
  printf '%s' "$cfg"
}

# GitHub — PAT capability probe. A "valid" GitHub token is not enough: the
# release needs specific CAPABILITIES on the release repo. Presence and even a
# generic /user liveness check both pass for a token that cannot do the job —
# the same failure shape as the dead npm token. So probe the release-critical
# capabilities directly:
#   - repo read           GET /repos/{repo}
#   - contents write       .permissions.push on that repo (push bumps + create
#                          the GitHub Release) — read-only signal, no mutation
# 403 is DENIED (capability not granted), distinct from 404 ABSENT and a
# transport error (INDETERMINATE) — a silent "no output" must never read as OK.
probe_github() {
  local json="$1" token cfg status body push
  token="$(secret_field "$json" token)"
  [[ -z "$token" ]] && { printf 'MALFORMED\tmissing token field\n'; return; }
  cfg="$(github_cfg "$token")"; body="$(new_tmp)"
  # curl's -w already prints 000 on transport failure; default empty to 000 too
  # (don't `|| echo 000`, which would append a second 000 → "000000").
  status="$(curl -s -o "$body" -w '%{http_code}' --max-time "$CURL_TIMEOUT" -A "$UA" -K "$cfg" \
            "https://api.github.com/repos/$GITHUB_RELEASE_REPO" 2>/dev/null)"; status="${status:-000}"
  rm -f "$cfg"
  case "$status" in
    2*) : ;;
    401) rm -f "$body"; printf 'REJECTED\ttoken refused by api.github.com (HTTP 401)\n'; return ;;
    403) rm -f "$body"; printf 'DENIED\ttoken cannot read %s (HTTP 403)\n' "$GITHUB_RELEASE_REPO"; return ;;
    404) rm -f "$body"; printf 'ABSENT\t%s not visible to this token (HTTP 404)\n' "$GITHUB_RELEASE_REPO"; return ;;
    000) rm -f "$body"; printf 'INDETERMINATE\tcould not reach api.github.com (network/DNS)\n'; return ;;
    *)   rm -f "$body"; printf 'INDETERMINATE\tunexpected HTTP %s from api.github.com\n' "$status"; return ;;
  esac
  push="$(jq -r '.permissions.push // false' "$body" 2>/dev/null)"
  rm -f "$body"
  if [[ "$push" == "true" ]]; then
    printf 'VALID\trepo read ok + contents-write ok (push bumps / gh release create) on %s\n' "$GITHUB_RELEASE_REPO"
  else
    printf 'DENIED\trepo read ok but NO contents-write permission — pushing bumps and gh release create would fail\n'
  fi
}

# GitHub — Actions-variables capability of the SAME PAT. Reported separately as
# a non-blocking WARNING: it only affects the Dependabot release-in-flight gate,
# not any publication step. 403 here means a fine-grained PAT missing the
# "Variables: read and write" repository permission (measured on the live token).
probe_github_actions_vars() {
  local json="$1" token cfg status
  token="$(secret_field "$json" token)"
  [[ -z "$token" ]] && { printf 'MALFORMED\tmissing token field\n'; return; }
  cfg="$(github_cfg "$token")"
  status="$(http_status -K "$cfg" "https://api.github.com/repos/$GITHUB_RELEASE_REPO/actions/variables")"
  rm -f "$cfg"
  case "$status" in
    2*)  printf 'VALID\tActions variables readable on %s\n' "$GITHUB_RELEASE_REPO" ;;
    403) printf "DENIED\tPAT lacks 'Variables: read and write' (HTTP 403); affects ONLY the Dependabot release-in-flight gate, not publication — fix: add 'Variables: read and write' to the fine-grained PAT\n" ;;
    404) printf 'ABSENT\tActions variables endpoint not found (HTTP 404)\n' ;;
    401) printf 'REJECTED\ttoken refused by api.github.com (HTTP 401)\n' ;;
    000) printf 'INDETERMINATE\tcould not reach api.github.com (network/DNS)\n' ;;
    *)   printf 'INDETERMINATE\tunexpected HTTP %s from api.github.com\n' "$status" ;;
  esac
}

# Docker Hub — username/token → POST /v2/users/login (returns a JWT; no mutation).
probe_dockerhub() {
  local json="$1" user token body status
  user="$(secret_field "$json" username)"; token="$(secret_field "$json" token)"
  [[ -z "$user" || -z "$token" ]] && { printf 'MALFORMED\tmissing username/token field\n'; return; }
  body="$(new_tmp)"
  # Pass the credential through the environment, not jq's argv: /proc/<pid>/environ
  # is owner-only, whereas /proc/<pid>/cmdline is world-readable.
  DH_U="$user" DH_P="$token" jq -n '{username:env.DH_U,password:env.DH_P}' > "$body"
  status="$(http_status -X POST -H 'Content-Type: application/json' -d @"$body" 'https://hub.docker.com/v2/users/login')"
  rm -f "$body"
  classify_http "$status" "hub.docker.com issued a login token"
}

# Registry-token liveness of the GHCR basic-auth credential. Requesting a scope
# the token lacks does NOT 403 — a Docker v2 token service returns 200 with a
# NARROWED grant — and GHCR's token is opaque (no decodable access claim), so
# this proves only "the credential authenticates", not push capability. Echoes
# the HTTP status.
ghcr_registry_status() {
  local user="$1" token="$2" cfg st
  cfg="$(new_tmp)"; printf 'user = "%s:%s"\n' "$user" "$token" > "$cfg"
  st="$(http_status -K "$cfg" 'https://ghcr.io/token?service=ghcr.io&scope=repository:mock-server/mockserver:push,pull')"
  rm -f "$cfg"
  printf '%s' "$st"
}

# Decide GHCR push capability from a classic-PAT `x-oauth-scopes` value. Pure
# (no network) so --self-test can prove it discriminates a push-capable token
# from a pull-only one. `write:packages` is required to push to GHCR.
ghcr_scope_verdict() {
  local scopes="$1"
  if grep -q ',write:packages,' <<<",${scopes},"; then
    printf 'VALID\tGitHub PAT authenticated with write:packages scope (GHCR push capability confirmed)\n'
  else
    printf 'REJECTED\tPAT authenticated but lacks write:packages scope — GHCR push (ECR-public mirror + Helm OCI) would fail\n'
  fi
}

# GHCR — the ghcr-token is consumed only to PUSH (ECR-public mirror + Helm OCI),
# so a pull-only or write-revoked token that still authenticates is the 8.0.0
# trap. GHCR issues an OPAQUE registry token (no decodable access claim), so we
# cannot read push capability off the registry response. Instead introspect the
# GitHub PAT's own scopes, read-only, via the `x-oauth-scopes` header on
# GET /user — the classic-PAT equivalent of the `.permissions.push` check used
# for the GitHub token: `write:packages` must be present to push to GHCR.
#   - scopes present + write:packages   -> VALID (push capability confirmed)
#   - scopes present, no write:packages -> REJECTED (push would fail)
#   - no x-oauth-scopes header (fine-grained PAT): capability not introspectable
#     read-only -> fall back to registry liveness and report VALID(SHAPE), the
#     same honesty applied to PyPI/RubyGems, rather than a firm VALID.
probe_ghcr() {
  local json="$1" user token cfg dump status scopes scopes_hdr rst hedge
  user="$(secret_field "$json" username)"; token="$(secret_field "$json" token)"
  [[ -z "$user" || -z "$token" ]] && { printf 'MALFORMED\tmissing username/token field\n'; return; }
  cfg="$(new_tmp)"; printf 'header = "Authorization: Bearer %s"\n' "$token" > "$cfg"
  dump="$(new_tmp)"
  # curl's -w already prints 000 on transport failure; default empty to 000 too
  # (don't `|| echo 000`, which would append a second 000 → "000000").
  status="$(curl -s -D "$dump" -o /dev/null -w '%{http_code}' --max-time "$CURL_TIMEOUT" \
            -A "$UA" -K "$cfg" 'https://api.github.com/user' 2>/dev/null)"; status="${status:-000}"
  # Was the x-oauth-scopes header present at all? (absent => fine-grained PAT;
  # present-but-empty => classic PAT with no scopes). Then normalise its value:
  # strip name, CR, and spaces; comma-wrap for match.
  scopes_hdr=absent; grep -qi '^x-oauth-scopes:' "$dump" && scopes_hdr=present
  scopes="$(grep -i '^x-oauth-scopes:' "$dump" | sed 's/^[^:]*://; s/\r//g; s/ //g' | tr -d '\n')"
  rm -f "$cfg" "$dump"
  case "$status" in
    2*)
      if [[ -n "$scopes" ]]; then
        ghcr_scope_verdict "$scopes"
      else
        rst="$(ghcr_registry_status "$user" "$token")"
        if [[ "$rst" == 2* ]]; then
          # Two indistinguishable-at-push-time cases, but the header tells them
          # apart for the operator; the hedge (VALID(SHAPE)) is the same for both.
          if [[ "$scopes_hdr" == present ]]; then
            hedge="empty x-oauth-scopes header (classic PAT with no scopes granted)"
          else
            hedge="no x-oauth-scopes header (fine-grained PAT)"
          fi
          printf 'VALID(SHAPE)\tregistry authenticated (HTTP %s) but push capability is not read-only-introspectable: %s\n' "$rst" "$hedge"
        else
          printf 'REJECTED\tGHCR registry refused the credential (HTTP %s)\n' "$rst"
        fi
      fi
      ;;
    401) printf 'REJECTED\tGitHub API refused the token (HTTP 401)\n' ;;
    000) printf 'INDETERMINATE\tcould not reach api.github.com (network/DNS)\n' ;;
    *)
      rst="$(ghcr_registry_status "$user" "$token")"
      if [[ "$rst" == 2* ]]; then
        printf 'VALID(SHAPE)\tregistry authenticated (HTTP %s); GitHub scope introspection unavailable (api.github.com HTTP %s)\n' "$rst" "$status"
      elif [[ "$rst" == 401 || "$rst" == 403 ]]; then
        printf 'REJECTED\tGHCR registry refused the credential (HTTP %s)\n' "$rst"
      else
        printf 'INDETERMINATE\tcould not establish GHCR capability (api %s, registry %s)\n' "$status" "$rst"
      fi
      ;;
  esac
}

# GPG signing key — base64-encoded in the secret. Import into a throwaway
# keyring to prove it parses, then a loopback test-sign of one byte to /dev/null
# to prove the passphrase unlocks it. No network, nothing published.
probe_gpg() {
  local json="$1" gh keyfile passfile keyid rc
  command -v gpg >/dev/null 2>&1 || { printf 'INDETERMINATE\tgpg not installed on this host\n'; return; }
  [[ -z "$(secret_field "$json" key)" ]] && { printf 'MALFORMED\tmissing key field\n'; return; }
  gh="$(new_tmpdir)"; chmod 700 "$gh"; keyfile="$gh/key.gpg"; passfile="$gh/pass"
  secret_field "$json" key | base64 -d > "$keyfile" 2>/dev/null
  # Passphrase to a private file read via --passphrase-file, never on argv.
  secret_field "$json" passphrase > "$passfile"
  if ! GNUPGHOME="$gh" gpg --batch --import "$keyfile" >/dev/null 2>&1; then
    rm -rf "$gh"; printf 'MALFORMED\tkey did not import (not a valid base64 GPG key)\n'; return
  fi
  keyid="$(GNUPGHOME="$gh" gpg --batch --list-secret-keys --with-colons 2>/dev/null | awk -F: '/^sec:/{print $5; exit}')"
  if [[ -z "$keyid" ]]; then
    rm -rf "$gh"; printf 'MALFORMED\timported but no secret key present\n'; return
  fi
  printf 'x' | GNUPGHOME="$gh" gpg --batch --yes --pinentry-mode loopback \
        --passphrase-file "$passfile" -u "$keyid" -o /dev/null -s - 2>/dev/null
  rc=$?
  rm -rf "$gh"
  if [[ $rc -eq 0 ]]; then
    printf 'VALID\tkey imported and passphrase unlocked it (test-sign)\n'
  else
    printf 'REJECTED\tkey imported but passphrase did not unlock it\n'
  fi
}

# cosign image-signing key — encrypted private key. Derive its public key to
# prove the password decrypts it. No signing. Without cosign on PATH it fetches
# the release's pinned, checksummed cosign (resolve_cosign, _lib.sh) into WORKDIR.
probe_cosign() {
  local json="$1" pw keyfile cosign_bin
  [[ -z "$(secret_field "$json" key)" ]] && { printf 'MALFORMED\tmissing key field\n'; return; }
  cosign_bin="$(resolve_cosign "$WORKDIR/cosign")" \
    || { printf 'INDETERMINATE\tcould not obtain the pinned cosign %s (download or checksum failed)\n' "$COSIGN_VERSION"; return; }
  pw="$(secret_field "$json" password)"
  keyfile="$(new_tmp)"
  secret_field "$json" key > "$keyfile"
  if COSIGN_PASSWORD="$pw" "$cosign_bin" public-key --key "$keyfile" >/dev/null 2>&1; then
    rm -f "$keyfile"; printf 'VALID\tpassword decrypted the key (public key derived)\n'
  else
    rm -f "$keyfile"; printf 'REJECTED\tkey present but password did not decrypt it\n'
  fi
}

# TOTP release-authorization seed — base32 shape check ONLY. We deliberately do
# not generate or submit a code; we only confirm the seed decodes to a usable
# HMAC key so the release-gate step will be able to.
probe_totp() {
  local json="$1" seedfile result
  [[ -z "$(secret_field "$json" seed)" ]] && { printf 'MALFORMED\tmissing seed field\n'; return; }
  # Pass the seed via a private temp file, not argv/stdin, so it never reaches a
  # process listing and the heredoc keeps python's stdin for its own code.
  seedfile="$(new_tmp)"
  secret_field "$json" seed > "$seedfile"
  result="$(python3 - "$seedfile" <<'PY'
import base64, sys
s = open(sys.argv[1]).read().strip().upper().replace(' ', '').replace('-', '')
s += '=' * ((8 - len(s) % 8) % 8)
try:
    key = base64.b32decode(s, casefold=True)
except Exception:
    print('MALFORMED\tseed is not valid base32'); sys.exit(0)
if len(key) >= 10:
    print('VALID(SHAPE)\tseed is valid base32 (%d bytes); code not generated by design' % len(key))
else:
    print('MALFORMED\tbase32 seed too short (%d bytes)' % len(key))
PY
)"
  rm -f "$seedfile"
  printf '%s\n' "$result"
}

# Website deploy role — assume-role is read-only (returns short-lived creds,
# mutates nothing). A success proves the role_arn + external_id are live. From a
# non-release identity the trust policy denies the assume; that is a property of
# WHERE the probe runs, not of the credential, so AccessDenied is INDETERMINATE
# (re-run on the release queue to verify), never REJECTED.
probe_website_role() {
  local json="$1" arn ext errf rc
  arn="$(secret_field "$json" role_arn)"
  [[ -z "$arn" ]] && { printf 'MALFORMED\tmissing role_arn field\n'; return; }
  [[ "$arn" =~ ^arn:aws:iam::[0-9]{12}:role/ ]] || { printf 'MALFORMED\trole_arn is not a well-formed IAM role ARN\n'; return; }
  ext="$(secret_field "$json" external_id)"
  local -a args=(--role-arn "$arn" --role-session-name credcheck-probe --duration-seconds 900 --output json)
  [[ -n "$ext" ]] && args+=(--external-id "$ext")
  [[ -n "${AWS_PROFILE:-}" ]] && args+=(--profile "$AWS_PROFILE")
  errf="$(new_tmp)"
  aws sts assume-role "${args[@]}" >/dev/null 2>"$errf"; rc=$?
  if [[ $rc -eq 0 ]]; then
    rm -f "$errf"; printf 'VALID\tassume-role succeeded (role_arn + external_id live)\n'; return
  fi
  if grep -qE 'AccessDenied|is not authorized to perform: sts:AssumeRole' "$errf" 2>/dev/null; then
    rm -f "$errf"; printf 'INDETERMINATE\tcaller not trusted to assume from here; verify on the release queue\n'; return
  fi
  rm -f "$errf"; printf 'INDETERMINATE\tassume-role could not complete (AWS session/other)\n'
}

# --- Registries with NO read-only identity endpoint ---------------------------
# For these we perform the closest safe check (a shape check) and say so plainly
# rather than invent a probe that proves nothing. A well-formed but revoked
# credential here reads as VALID(SHAPE); that limitation is stated in the NOTE.

# PyPI — API token. No whoami exists; tokens are macaroons prefixed `pypi-`.
probe_pypi() {
  local json="$1" token
  token="$(secret_field "$json" token)"
  [[ -z "$token" ]] && { printf 'MALFORMED\tmissing token field\n'; return; }
  if [[ "$token" == pypi-* && ${#token} -gt 16 ]]; then
    printf 'VALID(SHAPE)\tpypi- token shape ok; PyPI has no read-only identity endpoint to prove liveness\n'
  else
    printf 'MALFORMED\ttoken is not a pypi- prefixed API token\n'
  fi
}

# RubyGems — API key. profile/me.json needs an "index" scope a push-only key
# lacks, so a 401 there does NOT mean the key is dead. Probe it: 200 is a firm
# VALID; otherwise fall back to a shape check (can't distinguish push-scoped-live
# from revoked without publishing).
probe_rubygems() {
  local json="$1" key cfg status
  key="$(secret_field "$json" api_key)"
  [[ -z "$key" ]] && { printf 'MALFORMED\tmissing api_key field\n'; return; }
  cfg="$(auth_header_cfg "Authorization: $key")"
  status="$(http_status -K "$cfg" 'https://rubygems.org/api/v1/profile/me.json')"
  rm -f "$cfg"
  if [[ "$status" == 2* ]]; then
    printf 'VALID\trubygems.org identified the key\n'
  elif [[ ${#key} -ge 32 ]]; then
    printf 'VALID(SHAPE)\tkey shape ok; a push-scoped key cannot read profile/me so liveness is unverifiable (HTTP %s)\n' "$status"
  else
    printf 'MALFORMED\tapi_key is implausibly short\n'
  fi
}

# --- Optional (soft-fail) channels -------------------------------------------
# These publish with `soft_fail: true` in the pipeline: a failure never blocks a
# release, so they are reported for awareness but never change the exit code.

# Postman — API key → GET /me (clean identity endpoint).
probe_postman() {
  local json="$1" key cfg status
  key="$(secret_field "$json" api_key)"
  [[ -z "$key" ]] && { printf 'MALFORMED\tmissing api_key field\n'; return; }
  cfg="$(auth_header_cfg "X-Api-Key: $key")"
  status="$(http_status -K "$cfg" 'https://api.getpostman.com/me')"
  rm -f "$cfg"
  classify_http "$status" "api.getpostman.com identified the key"
}

# NuGet — the release pushes these ids with one key (client-dotnet.sh, tc-dotnet.sh).
NUGET_PACKAGE_IDS=(MockServerClient MockServer.Testcontainers)
NUGET_ROTATE_HINT="rotate it: docs/operations/publish-secrets-setup.md#rotate-the-key"

# Newest published version of a NuGet id (empty if it cannot be resolved).
nuget_latest_version() {
  local lower body st
  lower="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  body="$(new_tmp)"
  st="$(curl -s -o "$body" -w '%{http_code}' --max-time "$CURL_TIMEOUT" -A "$UA" \
        "https://api.nuget.org/v3-flatcontainer/$lower/index.json" 2>/dev/null)"
  [[ "$st" == 2* ]] && jq -r '.versions[-1] // empty' "$body" 2>/dev/null
  rm -f "$body"
}

# NuGet — the client's own symbol-push handshake (NuGetGallery ApiController):
#   1. POST create-verification-key/{id}/{ver} with the API key: 200 only if the
#      key is live (403 once expired) and push-scoped; returns a 1-day temp key.
#   2. GET verifykey/{id}/{ver} with that temp key: 200 only if the key's owner
#      may push the id; nuget.org deletes the temp key on use.
# Uploads nothing. The key's glob pattern is not introspectable this way.
probe_nuget() {
  local json="$1" key cfg tcfg id ver body hdr st msg warn="" fail="" T=$'\t'
  local -a proto=(-H 'X-NuGet-Protocol-Version: 4.1.0')
  key="$(secret_field "$json" api_key)"
  [[ -z "$key" ]] && { printf 'MALFORMED\tmissing api_key field\n'; return; }
  cfg="$(auth_header_cfg "X-NuGet-ApiKey: $key")"
  body="$(new_tmp)"; hdr="$(new_tmp)"; tcfg="$(new_tmp)"
  for id in "${NUGET_PACKAGE_IDS[@]}"; do
    ver="$(nuget_latest_version "$id")"
    [[ -z "$ver" ]] && { fail="INDETERMINATE${T}could not resolve a published $id version on api.nuget.org"; break; }
    st="$(curl -s -D "$hdr" -o "$body" -w '%{http_code}' --max-time "$CURL_TIMEOUT" -A "$UA" -K "$cfg" \
          -X POST -H 'Content-Length: 0' "${proto[@]}" \
          "https://www.nuget.org/api/v2/package/create-verification-key/$id/$ver" 2>/dev/null)"; st="${st:-000}"
    # Server text only (never a key); strip control chars, cap length.
    msg="$(head -c 200 "$body" 2>/dev/null | tr -d '\000-\037\177')"
    [[ -z "$warn" ]] && warn="$(grep -i '^x-nuget-warning:' "$hdr" 2>/dev/null | head -1 | sed 's/^[^:]*:[[:space:]]*//' | tr -d '\000-\037\177')"
    case "$st" in
      2*) : ;;
      401|403) fail="REJECTED${T}nuget.org refused the key (HTTP $st: ${msg:-no message}) — $NUGET_ROTATE_HINT"; break ;;
      000) fail="INDETERMINATE${T}could not reach www.nuget.org (network/DNS)"; break ;;
      *)   fail="INDETERMINATE${T}unexpected HTTP $st from nuget.org create-verification-key"; break ;;
    esac
    if ! jq -er '.Key // empty | select(test("\\A[A-Za-z0-9_-]+\\z")) | "header = \"X-NuGet-ApiKey: \(.)\""' "$body" > "$tcfg" 2>/dev/null; then
      fail="INDETERMINATE${T}nuget.org returned no usable verification key for $id"; break
    fi
    st="$(curl -s -o "$body" -w '%{http_code}' --max-time "$CURL_TIMEOUT" -A "$UA" -K "$tcfg" "${proto[@]}" \
          "https://www.nuget.org/api/v2/verifykey/$id/$ver" 2>/dev/null)"; st="${st:-000}"
    msg="$(head -c 200 "$body" 2>/dev/null | tr -d '\000-\037\177')"
    case "$st" in
      2*) : ;;
      403|409) fail="DENIED${T}key is live but cannot push $id (HTTP $st: ${msg:-no message})"; break ;;
      000) fail="INDETERMINATE${T}could not reach www.nuget.org (network/DNS)"; break ;;
      *)   fail="INDETERMINATE${T}unexpected HTTP $st from nuget.org verifykey for $id"; break ;;
    esac
  done
  rm -f "$cfg" "$tcfg" "$body" "$hdr"
  if [[ -n "$fail" ]]; then
    printf '%s\n' "$fail"
  elif [[ -n "$warn" ]]; then
    printf 'VALID\tWARNING: push ok for %s but nuget.org says: %s — %s\n' "${NUGET_PACKAGE_IDS[*]}" "$warn" "$NUGET_ROTATE_HINT"
  else
    printf 'VALID\tkey live + push-scoped and its owner can push %s\n' "${NUGET_PACKAGE_IDS[*]}"
  fi
}

# Generic non-empty shape check for optional channels whose registries expose no
# read-only, token-scoped identity endpoint.
probe_shape_only() {
  local json="$1" key
  # try the common field names in order
  for k in token api_key private_key key; do
    key="$(secret_field "$json" "$k")"
    [[ -n "$key" ]] && break
  done
  if [[ -n "$key" && ${#key} -ge 8 ]]; then
    printf 'VALID(SHAPE)\tpresent and non-trivial; no read-only identity endpoint to prove liveness\n'
  else
    printf 'MALFORMED\tmissing or implausibly short credential field\n'
  fi
}

# =============================================================================
# Credential catalogue — derived from .buildkite/release-pipeline.yml and
# scripts/release/components/*. Format: secret_id | required | probe | label
# `required=1` are steps that are NOT soft_fail: a bad credential turns the
# release red. `required=0` are soft_fail channels (advisory only).
# =============================================================================
CREDENTIALS=(
  # --- required: a failure here breaks the release -------------------------
  "mockserver-build/sonatype|1|probe_sonatype|Maven Central (Sonatype)"
  "mockserver-release/gpg-key|1|probe_gpg|Maven artifact GPG signing"
  "mockserver-release/npm-token|1|probe_npm|npm (mockserver-node / client)"
  "mockserver-release/github-token|1|probe_github|GitHub (push bumps + Release)"
  "mockserver-release/dockerhub|1|probe_dockerhub|Docker Hub"
  "mockserver-release/ghcr-token|1|probe_ghcr|GHCR (ECR-public mirror + Helm OCI)"
  "mockserver-release/cosign-key|1|probe_cosign|cosign (image + Helm signing)"
  "mockserver-build/pypi|1|probe_pypi|PyPI (mockserver-client)"
  "mockserver-build/rubygems|1|probe_rubygems|RubyGems (mockserver-client)"
  "mockserver-release/website-role|1|probe_website_role|Website + versioned site (S3/CloudFront)"
  "mockserver-release/totp-seed|1|probe_totp|Release TOTP authorization gate"
  # --- optional: soft_fail channels, advisory only -------------------------
  # Same github-token secret, but a distinct CAPABILITY: non-blocking warning.
  "mockserver-release/github-token|0|probe_github_actions_vars|GitHub Actions variables (Dependabot gate)"
  "mockserver-build/postman-api-key|0|probe_postman|Postman collection"
  "mockserver-release/swaggerhub|0|probe_shape_only|SwaggerHub (already soft_fail)"
  "mockserver-release/crates|0|probe_shape_only|crates.io (Rust client / TC)"
  "mockserver-release/nuget|0|probe_nuget|NuGet (.NET client / TC)"
  "mockserver-release/vsce|0|probe_shape_only|VS Code Marketplace"
  "mockserver-release/ovsx|0|probe_shape_only|Open VSX"
  "mockserver-release/jetbrains|0|probe_shape_only|JetBrains Marketplace"
  "mockserver-release/mcp-dns-key|0|probe_shape_only|MCP registry DNS"
  "mockserver-release/dashboard-analytics|0|probe_shape_only|Dashboard analytics (best-effort)"
)

# -----------------------------------------------------------------------------
# Self-test: prove the harness can still tell a dead credential from a live one
# and an unrunnable probe from a pass. Guards against the "check that proves
# nothing" failure this whole script exists to prevent. Network to npm,
# github.com (cosign) and nuget.org only.
# -----------------------------------------------------------------------------
run_self_test() {
  log_step "Self-test: does the probe distinguish good / bad / unrunnable?"
  local fails=0

  # 1) A deliberately corrupt npm token must classify as REJECTED, not VALID.
  local out st
  out="$(probe_npm '{"token":"npm_deliberatelyInvalidToken0000000000000000"}')"
  st="${out%%$'\t'*}"
  if [[ "$st" == "REJECTED" ]]; then
    echo "  ✓ corrupt npm token -> REJECTED"
  else
    echo "  ✗ corrupt npm token -> $st (expected REJECTED)"; fails=$((fails+1))
  fi

  # 2) A missing field must classify as MALFORMED, not VALID.
  out="$(probe_npm '{}')"; st="${out%%$'\t'*}"
  if [[ "$st" == "MALFORMED" ]]; then
    echo "  ✓ npm secret with no token -> MALFORMED"
  else
    echo "  ✗ npm secret with no token -> $st (expected MALFORMED)"; fails=$((fails+1))
  fi

  # 3) An unreachable endpoint must classify as INDETERMINATE, not VALID.
  out="$(classify_http 000 x)"; st="${out%%$'\t'*}"
  if [[ "$st" == "INDETERMINATE" ]]; then
    echo "  ✓ unreachable endpoint -> INDETERMINATE"
  else
    echo "  ✗ unreachable endpoint -> $st (expected INDETERMINATE)"; fails=$((fails+1))
  fi

  # 4) GHCR push-capability discrimination: a token WITH write:packages is VALID;
  #    a pull-only token (no write:packages) must be REJECTED, not VALID — the
  #    false-green the reviewer flagged (alive but pull-only).
  out="$(ghcr_scope_verdict 'repo,write:packages')"; st="${out%%$'\t'*}"
  if [[ "$st" == "VALID" ]]; then
    echo "  ✓ GHCR scopes with write:packages -> VALID"
  else
    echo "  ✗ GHCR scopes with write:packages -> $st (expected VALID)"; fails=$((fails+1))
  fi
  out="$(ghcr_scope_verdict 'repo,read:packages')"; st="${out%%$'\t'*}"
  if [[ "$st" == "REJECTED" ]]; then
    echo "  ✓ GHCR scopes without write:packages -> REJECTED"
  else
    echo "  ✗ GHCR scopes without write:packages -> $st (expected REJECTED)"; fails=$((fails+1))
  fi

  # 5) cosign: a host without cosign fetches the pinned binary; a tampered
  #    download is refused; and the probe tells a key its password decrypts
  #    (VALID) from a wrong password (REJECTED) and an unobtainable cosign
  #    (INDETERMINATE). Network to github.com only.
  local cosdir
  cosdir="$(new_tmpdir)"
  # curl stubbed to deliver a file that is not the pinned binary, so no download
  if ( curl() { local o=""; while [[ $# -gt 0 ]]; do [[ "$1" == "-o" ]] && { o="$2"; shift; }; shift; done; printf 'tampered' > "$o"; }
       download_pinned_cosign "$cosdir/tampered" ) 2>/dev/null || [[ -e "$cosdir/tampered" ]]; then
    echo "  ✗ cosign download with a wrong checksum was accepted"; fails=$((fails+1))
  else
    echo "  ✓ cosign download with a wrong checksum -> refused and removed"
  fi
  out="$( resolve_cosign() { return 1; }; probe_cosign '{"key":"x","password":"y"}' )"; st="${out%%$'\t'*}"
  if [[ "$st" == "INDETERMINATE" ]]; then
    echo "  ✓ cosign unobtainable -> INDETERMINATE"
  else
    echo "  ✗ cosign unobtainable -> $st (expected INDETERMINATE)"; fails=$((fails+1))
  fi
  if download_pinned_cosign "$WORKDIR/cosign" 2>/dev/null \
     && ( cd "$cosdir" && COSIGN_PASSWORD=self-test-pw "$WORKDIR/cosign" generate-key-pair >/dev/null 2>&1 ) \
     && [[ -s "$cosdir/cosign.key" ]]; then
    echo "  ✓ pinned cosign $COSIGN_VERSION downloaded, checksum verified, and runs"
    local key_json
    key_json="$(jq -n --rawfile key "$cosdir/cosign.key" --arg password self-test-pw '{key: $key, password: $password}')"
    out="$( resolve_cosign() { echo "$WORKDIR/cosign"; }; probe_cosign "$key_json" )"; st="${out%%$'\t'*}"
    if [[ "$st" == "VALID" ]]; then
      echo "  ✓ cosign key with its password -> VALID"
    else
      echo "  ✗ cosign key with its password -> $st (expected VALID)"; fails=$((fails+1))
    fi
    key_json="$(jq -n --rawfile key "$cosdir/cosign.key" --arg password wrong-pw '{key: $key, password: $password}')"
    out="$( resolve_cosign() { echo "$WORKDIR/cosign"; }; probe_cosign "$key_json" )"; st="${out%%$'\t'*}"
    if [[ "$st" == "REJECTED" ]]; then
      echo "  ✓ cosign key with a wrong password -> REJECTED"
    else
      echo "  ✗ cosign key with a wrong password -> $st (expected REJECTED)"; fails=$((fails+1))
    fi
  else
    echo "  ✗ could not download, verify and run the pinned cosign $COSIGN_VERSION"; fails=$((fails+1))
  fi
  rm -rf "$cosdir"

  # 6) NuGet: stubbed nuget.org. NS_CREATE = create-verification-key status;
  #    NS_CLIENT / NS_TC = verifykey status per id; NS_WARN = X-NuGet-Warning.
  #    The stub 403s any step sent with the wrong key. Then one live bogus key.
  nuget_stubbed() {
    ( curl() {
        local o="" d="" k="" url="" st
        while [[ $# -gt 0 ]]; do
          case "$1" in
            -o) o="$2"; shift ;; -D) d="$2"; shift ;; -K) k="$2"; shift ;;
            -w|-A|-H|-X|--max-time) shift ;; -*) ;; *) url="$1" ;;
          esac; shift
        done
        case "$url" in
          *v3-flatcontainer*) printf '{"versions":["9.2.0","9.3.0"]}' > "$o"; printf 200; return ;;
          */create-verification-key/*/9.3.0)
            grep -q 'oy2selftestkey' "$k" && st="${NS_CREATE:-200}" || st=403
            [[ -n "$d" ]] && { : > "$d"; [[ -n "${NS_WARN:-}" ]] && printf 'X-NuGet-Warning: %s\r\n' "$NS_WARN" > "$d"; }
            if [[ "$st" == 200 && -n "${NS_KEY:-}" ]]; then jq -n --arg k "$NS_KEY" '{Key:$k}' > "$o"
            elif [[ "$st" == 200 && -z "${NS_NOKEY:-}" ]]; then printf '{"Key":"tmp-verify-key","Expires":"x"}' > "$o"
            elif [[ "$st" == 200 ]]; then printf '{}' > "$o"; fi ;;
          */verifykey/MockServerClient/9.3.0)          st="${NS_CLIENT:-200}" ;;
          */verifykey/MockServer.Testcontainers/9.3.0) st="${NS_TC:-200}" ;;
          *) printf 404; return ;;
        esac
        [[ "$url" == */verifykey/* ]] && ! grep -q 'tmp-verify-key' "$k" && st=403
        [[ "$st" == 403 ]] && printf 'The specified API key has expired. Please create a new API key at https://www.nuget.org/account/apikeys.' > "$o"
        [[ "$st" == 000 ]] && { printf 000; return 28; }
        printf '%s' "$st"
      }
      probe_nuget '{"api_key":"oy2selftestkey"}' )
  }
  nuget_case() {  # expected-status note-substring description
    local want="$1" sub="$2" desc="$3" got
    got="${out%%$'\t'*}"
    if [[ "$got" == "$want" && "$out" == *"$sub"* ]]; then
      echo "  ✓ NuGet $desc -> $want"
    else
      echo "  ✗ NuGet $desc -> $got (expected $want with '$sub'): ${out#*$'\t'}"; fails=$((fails+1))
    fi
  }
  out="$(nuget_stubbed)"
  nuget_case VALID "can push MockServerClient MockServer.Testcontainers" "live key, both ids pushable"
  out="$(NS_CREATE=403 nuget_stubbed)"
  nuget_case REJECTED "has expired" "expired key (HTTP 403)"
  [[ "$out" == *"publish-secrets-setup.md"* ]] || { echo "  ✗ NuGet 403 note lacks the rotation pointer"; fails=$((fails+1)); }
  out="$(NS_TC=403 nuget_stubbed)"
  nuget_case DENIED "MockServer.Testcontainers" "key cannot push the TC id (HTTP 403)"
  out="$(NS_CREATE=000 nuget_stubbed)"
  nuget_case INDETERMINATE "network" "nuget.org unreachable"
  out="$(NS_CLIENT=500 nuget_stubbed)"
  nuget_case INDETERMINATE "HTTP 500" "unexpected HTTP 500"
  out="$(NS_NOKEY=1 nuget_stubbed)"
  nuget_case INDETERMINATE "no usable verification key" "200 without a verification key"
  out="$(NS_KEY=$'k"\nurl = "https://evil.example' nuget_stubbed)"
  nuget_case INDETERMINATE "no usable verification key" "verification key that would inject curl config"
  out="$(NS_WARN='Your API key expires in 3 days.' nuget_stubbed)"
  nuget_case VALID "WARNING: " "key about to expire"
  out="$(probe_nuget '{}')"
  nuget_case MALFORMED "api_key" "secret with no api_key"
  out="$(probe_nuget '{"api_key":"oy2deliberatelyinvalidkey0000000000000000000000000"}')"
  nuget_case REJECTED "HTTP 403" "corrupt key against live nuget.org"

  if [[ $fails -eq 0 ]]; then
    log_info "Self-test PASSED — the probe distinguishes good, bad, and unrunnable"
    return 0
  fi
  log_error "Self-test FAILED: $fails case(s) misclassified"
  return 1
}

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------
require_cmd aws
require_cmd jq
require_cmd curl
require_cmd python3

if [[ "${RUN_SELF_TEST:-false}" == "true" ]]; then
  run_self_test
  exit $?
fi

log_step "Release credential liveness probe (read-only)"

# Precondition: without an AWS session we cannot read any secret. Report the
# whole run as INDETERMINATE and stop — never emit a misleading "all present".
if ! aws sts get-caller-identity >/dev/null 2>&1; then
  log_error "No usable AWS session (aws sts get-caller-identity failed)."
  log_error "Cannot read any secret, so NO credential could be probed."
  log_error "Outcome: INDETERMINATE for every credential — re-authenticate (e.g. aws sso login) and re-run."
  exit 3
fi
log_info "AWS session OK: $(aws sts get-caller-identity --query Arn --output text 2>/dev/null)"
log_info "Region: $REGION${AWS_PROFILE:+  Profile: $AWS_PROFILE}"

declare -a ROWS=() WARNINGS=()
req_fail=0 req_indet=0 opt_note=0 opt_denied=0

for entry in "${CREDENTIALS[@]}"; do
  IFS='|' read -r sid required probe label <<< "$entry"
  [[ "$REQUIRED_ONLY" == "true" && "$required" == "0" ]] && continue

  read_secret "$sid"
  local_status=""; note=""
  case "$SECRET_READ_STATUS" in
    ABSENT)        local_status="ABSENT";        note="secret does not exist in Secrets Manager" ;;
    INDETERMINATE) local_status="INDETERMINATE"; note="secret could not be read (access/session)" ;;
    OK)
      out="$("$probe" "$SECRET_JSON")"
      local_status="${out%%$'\t'*}"
      note="${out#*$'\t'}"
      ;;
  esac

  # Tally against exit policy (required only).
  if [[ "$required" == "1" ]]; then
    case "$local_status" in
      VALID|VALID\(SHAPE\)) : ;;
      INDETERMINATE)        req_indet=$((req_indet+1)) ;;
      *)                    req_fail=$((req_fail+1)) ;;
    esac
  else
    case "$local_status" in
      VALID|VALID\(SHAPE\)) : ;;
      DENIED) opt_denied=$((opt_denied+1)); opt_note=$((opt_note+1)) ;;
      *)      opt_note=$((opt_note+1)) ;;
    esac
  fi
  # An advisory channel never fails the gate, so name a dead key loudly (DENIED
  # has its own summary line below).
  if [[ "$required" == "0" && "$local_status" =~ ^(REJECTED|MALFORMED)$ ]]; then
    WARNINGS+=("$label: $local_status — $note")
  elif [[ "$note" == WARNING:* ]]; then
    WARNINGS+=("$label: ${note#WARNING: }")
  fi

  local_req_label=$([[ "$required" == "1" ]] && echo "REQ" || echo "opt")
  ROWS+=("${local_status}|${local_req_label}|${label}|${sid}|${note}")
done

# -----------------------------------------------------------------------------
# Render table
# -----------------------------------------------------------------------------
echo
printf '%-14s  %-4s  %-40s  %s\n' "OUTCOME" "REQ" "CREDENTIAL" "SECRET-ID"
printf '%-14s  %-4s  %-40s  %s\n' "-------" "---" "----------" "---------"
for row in "${ROWS[@]}"; do
  IFS='|' read -r st req label sid note <<< "$row"
  marker=" "
  case "$st" in
    VALID)          marker="✓" ;;
    VALID\(SHAPE\)) marker="~" ;;
    DENIED)         marker="!" ;;
    REJECTED|MALFORMED|ABSENT) marker="✗" ;;
    INDETERMINATE)  marker="?" ;;
  esac
  printf '%s %-12s  %-4s  %-40s  %s\n' "$marker" "$st" "$req" "$label" "$sid"
  printf '                     %s\n' "$note"
done
echo
echo "Legend: ✓ VALID   ~ VALID(SHAPE, liveness not fully provable)   ! DENIED (present+valid but lacks a needed capability)   ✗ REJECTED/MALFORMED/ABSENT   ? INDETERMINATE (probe could not run)"
echo

# Surface non-blocking capability warnings prominently (regardless of verdict).
for w in ${WARNINGS[@]+"${WARNINGS[@]}"}; do
  log_error "WARNING: $w"
done
if [[ $opt_denied -gt 0 ]]; then
  log_error "WARNING: $opt_denied optional capability probe(s) returned DENIED — the credential is valid but lacks a capability. Advisory only; see the table note(s). Not a release blocker."
fi

# -----------------------------------------------------------------------------
# Verdict
# -----------------------------------------------------------------------------
if [[ $req_fail -gt 0 ]]; then
  log_error "FAIL: $req_fail required credential(s) REJECTED / MALFORMED / ABSENT — do NOT start the release."
  [[ $req_indet -gt 0 ]] && log_error "      (also $req_indet required credential(s) INDETERMINATE)"
  exit 1
fi
if [[ $req_indet -gt 0 ]]; then
  log_error "INCONCLUSIVE: $req_indet required credential(s) could not be probed (INDETERMINATE)."
  log_error "              Fail-closed: resolve the probe environment (tools/session/queue) and re-run before releasing."
  exit 2
fi
log_info "PASS: all required release credentials authenticated (VALID or VALID-SHAPE)."
[[ $opt_note -gt 0 ]] && log_info "Note: $opt_note optional (soft-fail) channel credential(s) are not VALID — advisory only, release not blocked."
exit 0
