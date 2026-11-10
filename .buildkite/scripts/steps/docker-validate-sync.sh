#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

DOCKERFILES=(
  "docker/Dockerfile"
  "docker/snapshot/Dockerfile"
  "docker/root/Dockerfile"
  "docker/root-snapshot/Dockerfile"
  "docker/local/Dockerfile"
  "docker/graaljs/Dockerfile"
  "docker/clustered/Dockerfile"
  "docker/aot/Dockerfile"
)

errors=0

# Joins '\' continuations into one logical instruction per line, as Docker does; a comment or blank
# line inside a continuation is dropped, a top-level comment line is kept.
logical_lines() {
  awk '/^[[:space:]]*#/ && buf == "" { print; next }
    buf != "" && /^[[:space:]]*(#|$)/ { next }
    { if (sub(/\\$/, "")) { buf = buf $0 " "; next } print buf $0; buf = "" }
    END { if (buf != "") print buf }' "$1"
}

for df in "${DOCKERFILES[@]}"; do
  filepath="$REPO_ROOT/$df"
  if [ ! -f "$filepath" ]; then
    echo "WARN: $df not found, skipping"
    continue
  fi

  if grep -qE 'CMD\s+\["-serverPort"' "$filepath"; then
    echo "FAIL: $df uses CMD [\"-serverPort\", ...] — must use ENV SERVER_PORT + CMD [] instead"
    errors=$((errors + 1))
  fi

  # Accept only the modern "ENV SERVER_PORT=1080" form. The legacy space-separated
  # "ENV SERVER_PORT 1080" is what BuildKit's LegacyKeyValueFormat lint flags, so the
  # Dockerfiles were migrated to "=" and this gate now asserts the migrated form (a bare
  # space would be a regression back to the deprecated syntax).
  if ! grep -qE 'ENV\s+SERVER_PORT=1080' "$filepath"; then
    echo "FAIL: $df missing 'ENV SERVER_PORT=1080'"
    errors=$((errors + 1))
  fi

  if ! grep -qE 'CMD\s+\[\s*\]' "$filepath"; then
    echo "FAIL: $df missing 'CMD []'"
    errors=$((errors + 1))
  fi

  if ! grep -q 'org.mockserver.cli.Main' "$filepath"; then
    echo "FAIL: $df missing 'org.mockserver.cli.Main' in ENTRYPOINT"
    errors=$((errors + 1))
  fi

  # Every image that runs org.mockserver.cli.Main must cap the JVM heap so the in-memory
  # request/expectation rings size off a bounded heap, otherwise the container is liable to be
  # OOM-SIGKILLed under load. The cap is 45%: under load ZGC grows to its full heap in unreclaimable
  # memory and native JVM + kernel socket memory need the rest, so 60% was OOM-killed at 512 MiB and
  # 50% peaked above the memory-floor test's 90% line on amd64 CI agents.
  # Assert the cap so it cannot drift in one variant (the docs promise this exact value).
  expected_pct="45.0"
  if grep -q 'org.mockserver.cli.Main' "$filepath"; then
    entrypoint_pcts="$(grep -oE '"-XX:MaxRAMPercentage=[0-9.]+"' "$filepath" || true)"
    if [ "$entrypoint_pcts" != "\"-XX:MaxRAMPercentage=${expected_pct}\"" ]; then
      echo "FAIL: $df must set exactly one '-XX:MaxRAMPercentage=${expected_pct}' heap cap in ENTRYPOINT (found: ${entrypoint_pcts:-none})"
      errors=$((errors + 1))
    fi
    # Netty's epoll/tcnative and JNA load native libraries; without this flag JDK 24+ prints
    # restricted-method WARNINGs on every start. Every CDS dump and AppCDS/AOT training command must
    # match the ENTRYPOINT, or CDS logs a flag mismatch at [error] level on every start. Checked per
    # command after joining '\' continuations, so reflowing a RUN instruction cannot hide one.
    logical="$(logical_lines "$filepath")"
    entrypoint_lines="$(grep -E '^ENTRYPOINT ' <<<"$logical" || true)"
    # Extension JARs mounted at /extensions (docs/code/extensions.md) load only if every image puts the
    # directory on its classpath, last, so an extension cannot shadow a MockServer or /libs class.
    if ! grep -qE '"-cp", "[^"]*:/libs/\*:/extensions/\*"' <<<"$entrypoint_lines"; then
      echo "FAIL: $df ENTRYPOINT classpath must end with :/libs/*:/extensions/*"
      errors=$((errors + 1))
    fi
    if ! grep -qF '"--enable-native-access=ALL-UNNAMED"' <<<"$entrypoint_lines"; then
      echo "FAIL: $df ENTRYPOINT must pass \"--enable-native-access=ALL-UNNAMED\""
      errors=$((errors + 1))
    fi
    archive_cmds="$(grep -E '^RUN ' <<<"$logical" | tr '&;|' '\n\n\n' \
      | grep -E "(^|[[:space:]/\"'])java[\"']?,?[[:space:]].*(-XX:(ArchiveClassesAtExit|AOTCacheOutput)=|-Xshare:dump)" || true)"
    if grep -qE -- '-XX:(SharedArchiveFile|AOTCache)=' <<<"$entrypoint_lines" && [ -z "$archive_cmds" ]; then
      echo "FAIL: $df ENTRYPOINT loads a CDS/AOT archive but no RUN instruction dumps or trains one"
      errors=$((errors + 1))
    fi
    missing_flag="$(grep -vF -- '--enable-native-access=ALL-UNNAMED' <<<"$archive_cmds" | grep -E '[^[:space:]]' || true)"
    if [ -n "$missing_flag" ]; then
      echo "FAIL: $df CDS dump / AppCDS / AOT training command must pass --enable-native-access=ALL-UNNAMED to match the ENTRYPOINT:"
      echo "$missing_flag" | sed 's/^[[:space:]]*/    /'
      errors=$((errors + 1))
    fi
  fi
done

# The image HEALTHCHECK must be the bundled static probe, never a second JVM: a probe JVM runs inside
# the container's memory cgroup every interval and pushed a 512 MiB container over its limit under
# load. Each image context carries a byte-identical copy of the canonical probe source.
PROBE_SOURCE="$REPO_ROOT/docker/healthcheck/mockserver-healthcheck.go"
for df in "${DOCKERFILES[@]}"; do
  filepath="$REPO_ROOT/$df"
  [ -f "$filepath" ] || continue
  if ! grep -qE '^  CMD \["/mockserver-healthcheck"\]$' "$filepath" \
     || ! grep -qE '^COPY --from=healthcheck /mockserver-healthcheck /mockserver-healthcheck$' "$filepath"; then
    echo "FAIL: $df HEALTHCHECK must run the bundled /mockserver-healthcheck probe (COPY --from=healthcheck + CMD [\"/mockserver-healthcheck\"])"
    errors=$((errors + 1))
  fi
  healthcheck_block="$(grep -A1 '^HEALTHCHECK' "$filepath" || true)"
  if grep -q 'java' <<<"$healthcheck_block"; then
    echo "FAIL: $df HEALTHCHECK starts a JVM — use the bundled /mockserver-healthcheck probe"
    errors=$((errors + 1))
  fi
  context_dir="$(dirname "$filepath")"
  if [ "$df" != "docker/Dockerfile" ] && ! cmp -s "$PROBE_SOURCE" "$context_dir/mockserver-healthcheck.go"; then
    echo "FAIL: $(dirname "$df")/mockserver-healthcheck.go is missing or differs from docker/healthcheck/mockserver-healthcheck.go"
    errors=$((errors + 1))
  fi
done

# The published images (and the docker/Dockerfile reference) trim the jar's natives and split it into
# own + deps jars with ONE shared script, so the size win cannot silently drift in one variant. Each
# context carries a byte-identical copy of it, and every ENTRYPOINT uses the split classpath.
JARPREP_SOURCE="$REPO_ROOT/docker/jarprep/mockserver-jarprep.sh"
JARPREP_DOCKERFILES=(
  "docker/Dockerfile"
  "docker/local/Dockerfile"
  "docker/graaljs/Dockerfile"
  "docker/clustered/Dockerfile"
  "docker/aot/Dockerfile"
)
for df in "${JARPREP_DOCKERFILES[@]}"; do
  filepath="$REPO_ROOT/$df"
  context_dir="$(dirname "$filepath")"
  copy="$context_dir/mockserver-jarprep.sh"
  [ "$df" = "docker/Dockerfile" ] && copy="$JARPREP_SOURCE"
  if ! cmp -s "$JARPREP_SOURCE" "$copy"; then
    echo "FAIL: ${copy#"$REPO_ROOT"/} is missing or differs from docker/jarprep/mockserver-jarprep.sh"
    errors=$((errors + 1))
  fi
  if ! grep -qE '^RUN sh /usr/local/bin/mockserver-jarprep\.sh ' "$filepath"; then
    echo "FAIL: $df must prepare its jar with mockserver-jarprep.sh (trimmed natives + own/deps split)"
    errors=$((errors + 1))
  fi
  logical="$(logical_lines "$filepath")"
  entrypoint_lines="$(grep -E '^ENTRYPOINT ' <<<"$logical" || true)"
  if ! grep -qF '"-cp", "/mockserver.jar:/mockserver-deps.jar:/libs/*:/extensions/*"' <<<"$entrypoint_lines"; then
    echo "FAIL: $df ENTRYPOINT must use the split classpath /mockserver.jar:/mockserver-deps.jar:/libs/*:/extensions/*"
    errors=$((errors + 1))
  fi
  # The jars must come from the jarprep stage: an image that also COPYs the untrimmed fat jar into its
  # runtime stage passes every other check here while shipping the old ~94 MB layer.
  for part in own deps; do
    if ! grep -qE "^COPY --from=jarprep /jarprep/${part}\\.jar " <<<"$logical"; then
      echo "FAIL: $df must COPY --from=jarprep /jarprep/${part}.jar"
      errors=$((errors + 1))
    fi
  done
  runtime_stage="$(awk 'toupper($0) ~ /^[[:space:]]*FROM[[:space:]]/{stage=""} {stage=stage $0 "\n"} END{printf "%s", stage}' <<<"$logical")"
  fat_copy="$(grep -iE '^[[:space:]]*COPY[[:space:]].*mockserver-netty-jar-with-dependencies\.jar' <<<"$runtime_stage" || true)"
  if [ -n "$fat_copy" ]; then
    echo "FAIL: $df's runtime stage COPYs the untrimmed fat jar; ship only the jarprep own/deps jars:"
    echo "$fat_copy" | sed 's/^[[:space:]]*/    /'
    errors=$((errors + 1))
  fi
  # An archive records its training classpath; a different one (e.g. reversed) is silently not mapped
  # under the default -Xshare:auto, so every AppCDS/AOT training command must use the ENTRYPOINT's order.
  training_cmds="$(grep -E '^RUN ' <<<"$logical" | tr '&;|' '\n\n\n' \
    | grep -E -- '-XX:(ArchiveClassesAtExit|AOTCacheOutput)=' || true)"
  wrong_cp="$(grep -vE -- '[[:space:]]-cp[[:space:]]+/mockserver\.jar:/mockserver-deps\.jar[[:space:]]+org\.mockserver\.cli\.Main([[:space:]]|$)' <<<"$training_cmds" \
    | grep -E '[^[:space:]]' || true)"
  if [ -n "$wrong_cp" ]; then
    echo "FAIL: $df AppCDS/AOT training must run -cp /mockserver.jar:/mockserver-deps.jar org.mockserver.cli.Main:"
    echo "$wrong_cp" | sed 's/^[[:space:]]*/    /'
    errors=$((errors + 1))
  fi
done

# Every image compiles the probe from a digest-pinned golang image (hard check) on the BUILD
# platform, cross-compiling to TARGETARCH: Go crashes under QEMU, so the stage must never run as the
# emulated leg of a multi-arch build. A defaulted 'ARG TARGETARCH=…' would pin one arch, so require
# the bare form. They should all pin the SAME image so scanners see one Go stdlib version;
# Dependabot's healthcheck-golang group bumps them in one PR, but a partial bump is only warned about.
golang_froms=""
for df in "${DOCKERFILES[@]}"; do
  pinned="$(grep -E '^FROM --platform=\$BUILDPLATFORM golang:[^ ]+@sha256:[0-9a-f]{64} AS healthcheck$' "$REPO_ROOT/$df" || true)"
  pinned_count="$(grep -c . <<<"$pinned" || true)"
  if [ "$pinned_count" -ne 1 ]; then
    echo "FAIL: $df must have exactly one digest-pinned 'FROM --platform=\$BUILDPLATFORM golang:<tag>@sha256:<digest> AS healthcheck' (found $pinned_count)"
    errors=$((errors + 1))
  fi
  stage="$(awk '/ AS healthcheck$/ {in_stage=1; next} /^FROM / {in_stage=0} in_stage' "$REPO_ROOT/$df")"
  if ! grep -qx 'ARG TARGETARCH' <<<"$stage" || ! grep -q 'GOARCH="\$GOARCH"' <<<"$stage" \
     || ! grep -qF 'GOARCH="${TARGETARCH:-' <<<"$stage" || ! grep -qF 'od -An -tu2 -j18 -N2' <<<"$stage"; then
    echo "FAIL: $df healthcheck stage must declare a bare 'ARG TARGETARCH', derive GOARCH=\"\${TARGETARCH:-...}\", build with GOARCH=\"\$GOARCH\" and check the binary's ELF machine (od -An -tu2 -j18 -N2)"
    errors=$((errors + 1))
  fi
  golang_froms="${golang_froms}${pinned}"$'\n'
done
golang_distinct="$(sort -u <<<"$golang_froms" | grep -c . || true)"
if [ "$golang_distinct" -gt 1 ]; then
  echo "WARNING: the ${#DOCKERFILES[@]} Dockerfiles pin $golang_distinct different golang images for the healthcheck stage — bring them to one digest (docs/infrastructure/docker.md, Docker HEALTHCHECK)"
fi

# Layered variants add files to the standard image and inherit everything else, so every check above
# holds for them by construction - as long as they never re-declare what they inherit.
LAYERED_DOCKERFILES=(
  "docker/http3/Dockerfile"
)
for df in "${LAYERED_DOCKERFILES[@]}"; do
  filepath="$REPO_ROOT/$df"
  if [ ! -f "$filepath" ]; then
    echo "FAIL: layered variant $df not found"
    errors=$((errors + 1))
    continue
  fi
  if [ "$(grep -iE '^[[:space:]]*FROM[[:space:]]' "$filepath" | tail -1)" != 'FROM ${BASE_IMAGE}' ]; then
    echo "FAIL: $df must end on 'FROM \${BASE_IMAGE}' so it inherits the standard image"
    errors=$((errors + 1))
  fi
  # The final stage may only COPY files in and relabel the analytics distribution.
  final_stage="$(awk 'toupper($0) ~ /^[[:space:]]*FROM[[:space:]]/{stage=""} {stage=stage $0 "\n"} END{printf "%s", stage}' "$filepath")"
  overrides="$(grep -iE '^[[:space:]]*(ENTRYPOINT|CMD|HEALTHCHECK|USER|ENV|EXPOSE|RUN|WORKDIR|VOLUME|STOPSIGNAL|SHELL|ADD|ONBUILD)[[:space:]]' <<<"$final_stage" \
    | grep -vxE 'ENV MOCKSERVER_DASHBOARD_ANALYTICS_DISTRIBUTION=[a-z0-9-]+' || true)"
  if [ -n "$overrides" ]; then
    echo "FAIL: $df's final stage re-declares an inherited setting or runs a build step (only COPY and the analytics ENV are allowed)"
    errors=$((errors + 1))
  fi
done

# A default on a stage-level BuildKit platform ARG (ARG TARGETARCH=amd64), an ENV of one, or a non-empty
# fallback such as ${TARGETARCH:-amd64} OVERRIDES the arch BuildKit injects on each buildx leg: the arm64
# images shipped x86_64 natives. Scans EVERY Dockerfile under docker/ on '\'-joined logical lines with
# comment lines dropped. Every target-platform RUN that fetches or trims natives must carry, IN THAT RUN,
# the uname case (with an exiting '*)' arm), the TARGETARCH cross-check and the od ELF comparison, each
# failing with 'exit 1'. This is a marker check: it proves the checks are present, not that they run.
NATIVE_CHECK_AWK='
  function fail(msg) { print "  stage " stage ": " msg }
  function strip_funcs(s,    out, rest, i, n, c, depth) {
    out = ""
    while (match(s, /[A-Za-z_][A-Za-z0-9_]*[[:space:]]*[(][)][[:space:]]*[{]/)) {
      out = out substr(s, 1, RSTART - 1); rest = substr(s, RSTART + RLENGTH); depth = 1; n = length(rest)
      for (i = 1; i <= n && depth > 0; i++) { c = substr(rest, i, 1); if (c == "{") depth++; else if (c == "}") depth-- }
      s = substr(rest, i)
    }
    return out s
  }
  function exits_before_fi(s,    t) {
    t = substr(s, RSTART + RLENGTH)
    if (match(t, /[;[:space:]]fi([;[:space:]]|$)/)) t = substr(t, 1, RSTART)
    return t ~ /exit 1([^0-9]|$)/
  }
  function check_run(s,    cb, e, outside, arms, n, i, wild) {
    s = strip_funcs(s)
    gsub("(echo|printf)[[:space:]]+" sq "[^" sq "]*" sq, "", s)
    if (!match(s, /case "[$][(]uname -m[)]" in/)) {
      fail("no arch taken from case \"$(uname -m)\" in")
    } else {
      cb = substr(s, RSTART + RLENGTH); e = index(cb, "esac")
      outside = substr(s, 1, RSTART - 1) (e ? substr(cb, e + 4) : "")
      cb = e ? substr(cb, 1, e - 1) : cb
      n = split(cb, arms, ";;"); wild = 0
      for (i = 1; i <= n; i++) if (arms[i] ~ /^[[:space:]]*[*][)]/) wild = (arms[i] ~ /exit 1([^0-9]|$)/) ? 1 : -1
      if (wild < 1) fail("the uname case has no *) arm that exits 1")
      if (outside ~ /(^|[^[:alnum:]_])(STAGE_ARCH|ELF_MACHINE)=/) fail("STAGE_ARCH or ELF_MACHINE is reassigned outside the uname case")
    }
    if (!match(s, /if \[ -n "[$][{]TARGETARCH:-[}]" \] && \[ "[$]TARGETARCH" != "[$]STAGE_ARCH" \]; then/) || !exits_before_fi(s))
      fail("no if [ -n \"${TARGETARCH:-}\" ] && [ \"$TARGETARCH\" != \"$STAGE_ARCH\" ]; then ... exit 1")
    if (s !~ /(;|do)[[:space:]]+ACTUAL="[$][(]od -An -tu2 -j18 -N2 "[$]so"[)]";/)
      fail("no ACTUAL=\"$(od -An -tu2 -j18 -N2 \"$so\")\" read of each .so")
    if (!match(s, /if \[ "[$][{]ACTUAL##[*] [}]" != "[$]ELF_MACHINE" \]; then/) || !exits_before_fi(s))
      fail("no if [ \"${ACTUAL##* }\" != \"$ELF_MACHINE\" ]; then ... exit 1")
  }
  function end_stage() {
    if (stage != "" && !buildplatform && !natives && stage ~ /^(download|copy|tcnative|jarprep)$/ && !((file ":" stage) in exempt))
      fail("is a native-stage name but fetches or trims no natives with the arch-check markers")
  }
  BEGIN { n = split(exempt_list, x, " "); for (i = 1; i <= n; i++) exempt[x[i]] = 1 }
  /^[[:space:]]*#/ { next }
  toupper($1) == "FROM" { end_stage(); stage = tolower($NF); buildplatform = ($0 ~ /--platform=[$][{]?BUILDPLATFORM/); natives = 0; next }
  buildplatform || toupper($1) != "RUN" { next }
  /mockserver-jarprep[.]sh/ { natives = 1; next }
  /netty-tcnative|zip( -q)? -d / { natives = 1; check_run($0) }
  END { end_stage() }'
# docker/aot ships no tcnative (JDK TLS), so its download/copy stages legitimately carry no markers.
NATIVE_FREE_STAGES="docker/aot/Dockerfile:download docker/aot/Dockerfile:copy"
all_dockerfiles="$(find "$REPO_ROOT/docker" -type f -name 'Dockerfile*' | LC_ALL=C sort)"
if [ -z "$all_dockerfiles" ]; then
  echo "FAIL: found no Dockerfile under docker/ — the platform-ARG check scanned nothing"
  errors=$((errors + 1))
fi
while IFS= read -r filepath; do
  [ -n "$filepath" ] || continue
  df="${filepath#"$REPO_ROOT"/}"
  code="$(logical_lines "$filepath" | awk '!/^[[:space:]]*#/ { print NR ": " $0 }')"
  defaulted="$(grep -iE '^[0-9]+: *(ARG|ENV)[[:space:]](.*[[:space:]])?(TARGET|BUILD)(ARCH|OS|PLATFORM|VARIANT)[=[:space:]]' <<<"$code" || true)"
  if [ -n "$defaulted" ]; then
    echo "FAIL: $df gives a BuildKit platform ARG a default (or sets it with ENV), which overrides the per-platform value — declare it bare:"
    echo "$defaulted" | cut -c1-200
    errors=$((errors + 1))
  fi
  fallback="$(grep -E '[$][{](TARGET|BUILD)(ARCH|OS|PLATFORM|VARIANT):?[-=]([^$}]|[$][^(])' <<<"$code" || true)"
  if [ -n "$fallback" ]; then
    echo "FAIL: $df falls back to a fixed or variable arch/OS, which pins every buildx leg to it — fall back to \$(uname -m) or \$(go env ...) instead:"
    echo "$fallback" | cut -c1-200
    errors=$((errors + 1))
  fi
  unchecked="$(logical_lines "$filepath" | awk -v sq="'" -v file="$df" -v exempt_list="$NATIVE_FREE_STAGES" "$NATIVE_CHECK_AWK")"
  if [ -n "$unchecked" ]; then
    echo "FAIL: $df fetches or trims native .so files without the arch-check markers in the same RUN:"
    echo "$unchecked"
    errors=$((errors + 1))
  fi
done <<<"$all_dockerfiles"

# The shared jar-prep script (docker/jarprep/, when present) trims natives outside any Dockerfile, so a
# stage that calls it is only as safe as the script: it must carry the same three markers.
JARPREP_SCRIPT="$REPO_ROOT/docker/jarprep/mockserver-jarprep.sh"
if [ -f "$JARPREP_SCRIPT" ]; then
  jarprep_code="$(grep -v '^[[:space:]]*#' "$JARPREP_SCRIPT" || true)"
  for marker in 'case "$(uname -m)" in' '!= "$STAGE_ARCH" ]' 'od -An -tu2 -j18 -N2' '!= "$ELF_MACHINE" ]'; do
    if ! grep -qF -- "$marker" <<<"$jarprep_code"; then
      echo "FAIL: docker/jarprep/mockserver-jarprep.sh is missing the arch-check marker: $marker"
      errors=$((errors + 1))
    fi
  done
fi

# The published images must ship the mockserver-netty-docker jar (JNA unrelocated): libjnidispatch
# cannot bind to the library jar's relocated JNA, so SO_ORIGINAL_DST and eBPF lookups would silently
# fall back to conntrack. jarprep refuses the library jar at build time, and every script that stages
# a published image's jar must stage the image jar, never a local library-jar build.
if [ -f "$JARPREP_SCRIPT" ]; then
  # The exact un-negated test, then `fail` as its only statement: an `if !`, an extra condition or a
  # statement before the fail would all leave the refusal unbound.
  refusal="$(grep -v '^[[:space:]]*#' "$JARPREP_SCRIPT" | grep -v '^[[:space:]]*$' | awk -v want="if unzip -Z1 \"\$JAR\" 'shaded_package/com/sun/jna/Native.class' >/dev/null 2>&1; then" '
    state == 1 { state = ($1 == "fail") ? 2 : -1; next }
    state == 2 { result = ($1 == "fi" ? "bound" : "unbound"); state = 3; next }
    state == 3 { next }
    { line = $0; sub(/^[[:space:]]+/, "", line) }
    line == want { state = 1 }
    END { if (result != "") print result }')"
  if [ "$refusal" != "bound" ]; then
    echo "FAIL: docker/jarprep/mockserver-jarprep.sh must refuse a jar with relocated JNA (if unzip -Z1 \"\$JAR\" 'shaded_package/com/sun/jna/Native.class' ...; then followed by fail)"
    errors=$((errors + 1))
  fi
  if [ "$(grep -cxF 'JNA_ROOT="com/sun/jna/"' "$JARPREP_SCRIPT")" -ne 1 ] \
     || ! grep -qF 'grep -qx "${JNA_ROOT}Native.class" || fail' "$JARPREP_SCRIPT"; then
    echo "FAIL: docker/jarprep/mockserver-jarprep.sh must set JNA_ROOT=\"com/sun/jna/\" once and fail unless deps.jar has \${JNA_ROOT}Native.class"
    errors=$((errors + 1))
  fi
fi
IMAGE_JAR_STAGERS=(
  "scripts/release/components/docker.sh"
  ".buildkite/scripts/steps/java-docker-push-snapshot.sh"
  ".buildkite/scripts/build-local-mockserver-image.sh"
  "docker/local/local_docker_build.sh"
)
for stager in "${IMAGE_JAR_STAGERS[@]}"; do
  code="$(grep -v '^[[:space:]]*#' "$REPO_ROOT/$stager" 2>/dev/null || true)"
  if ! grep -qF 'mockserver-netty-docker' <<<"$code"; then
    echo "FAIL: $stager must stage the mockserver-netty-docker jar (JNA unrelocated) into the image context"
    errors=$((errors + 1))
  fi
  if grep -qF 'mockserver-netty-no-dependencies/target' <<<"$code"; then
    echo "FAIL: $stager stages a local mockserver-netty-no-dependencies build, whose relocated JNA cannot load - stage mockserver-netty-docker"
    errors=$((errors + 1))
  fi
done

# Every external base image, builder stages included, is pinned by index digest so an upstream tag
# move cannot change a build unreviewed; Dependabot's docker ecosystem proposes the digest bumps.
# A FROM naming an earlier stage, a build ARG (${...}) or scratch is not an external image.
for df in "${DOCKERFILES[@]}" "${LAYERED_DOCKERFILES[@]}" "docker/webhook/Dockerfile"; do
  unpinned="$(logical_lines "$REPO_ROOT/$df" | awk '
    toupper($1) != "FROM" { next }
    {
      image = ""
      for (i = 2; i <= NF; i++) if ($i !~ /^--/) { image = $i; break }
      external = !(image ~ /^\$/ || tolower(image) == "scratch" || (tolower(image) in stages))
      digest = image; sub(/.*@sha256:/, "", digest)  # no {64}: mawk lacks regex intervals
      if (external && (image !~ /@sha256:[0-9a-f]+$/ || length(digest) != 64)) print "    " $0
      for (j = i + 1; j < NF; j++) if (toupper($j) == "AS") stages[tolower($(j + 1))] = 1
    }')"
  if [ -n "$unpinned" ]; then
    echo "FAIL: $df has a base image not pinned by digest (FROM image:tag@sha256:<index digest>; docs/infrastructure/docker.md, Base Image Digest Pinning):"
    echo "$unpinned"
    errors=$((errors + 1))
  fi
done

if [ $errors -gt 0 ]; then
  echo ""
  echo "FAILED: $errors Dockerfile sync issue(s) found"
  echo "All Dockerfiles must use: ENV SERVER_PORT=1080 + CMD [] (not CMD [\"-serverPort\", ...])"
  exit 1
fi

echo "PASSED: All Dockerfiles are in sync"
