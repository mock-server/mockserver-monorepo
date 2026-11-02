locals {
  repository = "https://github.com/mock-server/mockserver-monorepo.git"

  pipelines = {
    "pipeline" = {
      name        = "MockServer"
      description = "Monorepo CI — path-based pipeline orchestrator"
      file        = ".buildkite/pipeline.yml"
      emoji       = ":pipeline:"
      trigger     = "code"
    }
    "java" = {
      name        = "MockServer Java"
      description = "Java server build and test (all Maven modules)"
      file        = ".buildkite/pipeline-java.yml"
      emoji       = ":maven:"
      trigger     = "none"
    }
    "ui" = {
      name        = "MockServer UI"
      description = "Dashboard React SPA — lint, test, build"
      file        = ".buildkite/pipeline-ui.yml"
      emoji       = ":react:"
      trigger     = "none"
    }
    "node" = {
      name        = "MockServer Node"
      description = "Node.js client and launcher — lint and typecheck"
      file        = ".buildkite/pipeline-node.yml"
      emoji       = ":node:"
      trigger     = "none"
    }
    "python" = {
      name        = "MockServer Python"
      description = "Python client — unit and integration tests"
      file        = ".buildkite/pipeline-python.yml"
      emoji       = ":python:"
      trigger     = "none"
    }
    "ruby" = {
      name        = "MockServer Ruby"
      description = "Ruby client — unit and integration tests"
      file        = ".buildkite/pipeline-ruby.yml"
      emoji       = ":ruby:"
      trigger     = "none"
    }
    "go" = {
      name        = "MockServer Go"
      description = "Go client and testcontainers module — unit tests"
      file        = ".buildkite/pipeline-go.yml"
      emoji       = ":golang:"
      trigger     = "none"
    }
    "dotnet" = {
      name        = "MockServer Dotnet"
      description = ".NET client and testcontainers module — unit tests"
      file        = ".buildkite/pipeline-dotnet.yml"
      emoji       = ":dotnet:"
      trigger     = "none"
    }
    "rust" = {
      name        = "MockServer Rust"
      description = "Rust client and testcontainers module — tests and clippy"
      file        = ".buildkite/pipeline-rust.yml"
      emoji       = ":rust:"
      trigger     = "none"
    }
    "php" = {
      name        = "MockServer PHP"
      description = "PHP client — unit tests"
      file        = ".buildkite/pipeline-php.yml"
      emoji       = ":php:"
      trigger     = "none"
    }
    "editors" = {
      name        = "MockServer Editors"
      description = "VS Code extension and JetBrains plugin — build and test"
      file        = ".buildkite/pipeline-editors.yml"
      emoji       = ":vscode:"
      trigger     = "none"
    }
    "maven-plugin" = {
      name        = "MockServer Maven Plugin"
      description = "Maven plugin build and test"
      file        = ".buildkite/pipeline-maven-plugin.yml"
      emoji       = ":maven:"
      trigger     = "none"
    }
    "perf-test" = {
      name        = "MockServer Performance Test"
      description = "Performance test validation + daily performance-regression run"
      file        = ".buildkite/pipeline-perf-test.yml"
      emoji       = ":chart_with_upwards_trend:"
      trigger     = "none"
    }
    "container-tests" = {
      name        = "MockServer Container Tests"
      description = "Container integration test script validation"
      file        = ".buildkite/pipeline-container-tests.yml"
      emoji       = ":docker:"
      trigger     = "none"
    }
    "website" = {
      name        = "MockServer Website"
      description = "Jekyll documentation site build"
      file        = ".buildkite/pipeline-website.yml"
      emoji       = ":jekyll:"
      trigger     = "none"
    }
    "infra" = {
      name        = "MockServer Infra"
      description = "Infrastructure, CI/CD, and shared config validation"
      file        = ".buildkite/pipeline-infra.yml"
      emoji       = ":terraform:"
      trigger     = "none"
    }
    "docker-push-maven" = {
      name        = "MockServer Build Image"
      description = "Build and push mockserver/mockserver:maven CI image"
      file        = ".buildkite/docker-push-maven.yml"
      emoji       = ":docker:"
      trigger     = "none"
    }
    "cleanup" = {
      name        = "MockServer Cleanup"
      description = "Cancel and delete Buildkite builds for closed/merged PRs"
      file        = ".buildkite/pipeline-cleanup.yml"
      emoji       = ":broom:"
      trigger     = "none"
    }
    "release" = {
      name        = "MockServer Release"
      description = "Automated release pipeline for MockServer"
      file        = ".buildkite/release-pipeline.yml"
      emoji       = ":rocket:"
      trigger     = "none"
    }
    "release-preflight" = {
      name        = "MockServer Release Preflight"
      description = "Validates release-queue and default-queue agents have every tool the release pipeline needs"
      file        = ".buildkite/release-preflight-pipeline.yml"
      emoji       = ":mag:"
      trigger     = "none"
    }
  }
}

resource "buildkite_pipeline_schedule" "cleanup_daily" {
  pipeline_id = buildkite_pipeline.pipeline["cleanup"].id
  label       = "Daily closed PR cleanup"
  cronline    = "0 6 * * *"
  branch      = "master"
  message     = "Scheduled: clean up closed PR builds"
}

# Nightly snapshot publish. Merge builds of mockserver-java build and test but publish nothing;
# this build also deploys the Sonatype SNAPSHOT, pushes the :snapshot images and uploads the
# snapshot bundles (pipeline-java.yml, gated on PUBLISH_SNAPSHOT). Its guard step replaces the
# rest of the build when the last publish already covers master's HEAD.
resource "buildkite_pipeline_schedule" "snapshot_publish_nightly" {
  pipeline_id = buildkite_pipeline.pipeline["java"].id
  label       = "Nightly snapshot publish"
  cronline    = "0 2 * * *"
  branch      = "master"
  message     = "Scheduled: nightly snapshot publish"
  env = {
    PUBLISH_SNAPSHOT = "true"
  }
}

# The perf schedules below follow local.perf_schedules_enabled (perf-schedules.tf).

# Daily performance-regression run. Fires every day at 04:00 UTC (off-peak,
# before the 06:00 cleanup). The build arrives with build.source == 'schedule',
# which the perf-test pipeline's commit-guard step keys off; the guard then
# dispatches the heavy run ONLY when master moved since the last successful run,
# so an idle day costs just the cheap guard query.
resource "buildkite_pipeline_schedule" "perf_regression_daily" {
  pipeline_id = buildkite_pipeline.pipeline["perf-test"].id
  label       = "Daily performance regression"
  cronline    = "0 4 * * *"
  branch      = "master"
  message     = "Scheduled: daily performance regression run"
  enabled     = local.perf_schedules_enabled
}

# Perf baseline-freshness safety-net. Gives the baseline-freshness assertion
# (.buildkite/scripts/steps/perf-baseline-freshness.sh, in pipeline-infra.yml) a
# GUARANTEED daily cadence of its own, instead of relying on someone happening to
# touch an infra path. It runs in mockserver-infra — a DIFFERENT pipeline from the
# perf producer above — precisely so it survives the producer dying: a decay
# detector that shares the producer's schedule dies with it. Fires at 16:00 UTC,
# well clear of the producer (04:00) and the cleanup sweep (06:00), so a dead
# producer schedule is detected within ~a day. The freshness step keys off the
# producer's Buildkite build liveness (not S3), so this schedule needs no extra
# credentials. A scheduled build runs only the freshness steps (pipeline-infra.yml).
# Autoscaling is unchanged: this only enqueues a build; the queue scales from and
# back to min_size = 0 on demand.
resource "buildkite_pipeline_schedule" "infra_baseline_freshness_daily" {
  pipeline_id = buildkite_pipeline.pipeline["infra"].id
  label       = "Daily perf baseline freshness check"
  cronline    = "0 16 * * *"
  branch      = "master"
  message     = "Scheduled: assert perf baseline freshness (safety-net)"
  enabled     = local.perf_schedules_enabled
}

# Weekly performance SOAK run (item 10 of the performance programme). soak.js runs
# for 2h on the `perf` queue under sustained load, surfacing slow degradation a
# short regression run cannot (data-plane p99 drift as the event log fills, leaks
# over hours) and measuring the cost of verify / retrieveRecordedRequests against a
# FULL log (item 10b).
#
# WHY SUNDAY 08:00 UTC — NOT the daily regression's slot. The `perf` queue has
# perf_max_size agents (terraform/buildkite-agents), one per machine. A daily
# regression build fans its four measurement steps out across those agents and can
# hold all of them at once, so a soak started in the 04:00 window would queue
# behind it. 08:00 is clear of the daily's worst case (every step at its timeout,
# per perf-test-guard.sh), of the 06:00 cleanup and of the 16:00 baseline-freshness
# check (which runs in the infra pipeline on a different queue anyway). Sunday is
# the lowest-commit day, so the commit-gated daily regression most often skips and
# an ad-hoc [perf-run]/[perf-inject] is least likely to contend for the perf
# agents. The [perf-soak] marker routes this build to the soak step ONLY
# (pipeline-perf-test.yml gates the daily guard and load test OFF for a [perf-soak]
# build), so the soak never triggers the daily regression dispatch. No queue-
# capacity change is needed: this only enqueues a build; the perf ASG scales from
# and back to min_size = 0 on demand (which MUST stay 0).
resource "buildkite_pipeline_schedule" "perf_soak_weekly" {
  pipeline_id = buildkite_pipeline.pipeline["perf-test"].id
  label       = "Weekly performance soak"
  cronline    = "0 8 * * 0"
  branch      = "master"
  message     = "Scheduled: weekly performance soak [perf-soak]"
  enabled     = local.perf_schedules_enabled
}

# Weekly PERF_XL run (item 44a): refreshes the multi-k6 headline series on perf-xl, as a
# manual PERF_XL=true [perf-run] build does. [perf-run] is required: without it the commit
# guard skips the build whenever the 04:00 daily already measured HEAD. It also forces the
# regression steps onto the `perf` queue, so Saturday 08:00 keeps clear of the daily's
# worst case and of Sunday's soak. [perf-xl-weekly] keeps it out of the baseline-freshness
# check's daily-producer set (perf-baseline-freshness.sh), as [perf-soak] does.
resource "buildkite_pipeline_schedule" "perf_xl_weekly" {
  pipeline_id = buildkite_pipeline.pipeline["perf-test"].id
  label       = "Weekly perf-xl headline run"
  cronline    = "0 8 * * 6"
  branch      = "master"
  message     = "Scheduled: weekly perf-xl headline run [perf-run] [perf-xl-weekly]"
  enabled     = local.perf_schedules_enabled
  env = {
    PERF_XL = "true"
  }
}

locals {
  # Audit finding F-BK-CLOUD-02: pipelines that load secrets via AWS Secrets
  # Manager must be PRIVATE so their build logs are not world-readable. The
  # auto-redaction Buildkite applies is pattern-based and not infallible.
  #
  # Pipelines that don't load secrets — or where the token is fully
  # protected by `set +x` guards and never appears in stdout/stderr —
  # can remain PUBLIC. Public visibility is necessary for OSS contributor
  # UX: external PR submitters need to see their own build logs to debug
  # failures; flipping these PRIVATE blocks them at a Buildkite login
  # page and forces maintainers to relay log excerpts manually.
  #
  # Note on "pipeline" (the top-level mockserver dispatcher):
  # generate-pipeline.sh fetches a Buildkite API token to query the last
  # successful build for path-based change detection. Mitigation is in
  # depth: `{ set +x; } 2>/dev/null` (F-BK-04) suppresses xtrace before
  # the secret fetch; the token is only sent in a curl `-H Authorization`
  # header (never via stdout-visible flags); Buildkite's secret redaction
  # covers known token patterns. Accepted residual risk: the only
  # plausible leak vector is an AWS CLI error mode that includes secret
  # material in the error message — which AWS CLI does not do in practice.
  # If a future refactor of generate-pipeline.sh removes the API-token
  # dependency entirely (e.g. by caching last-successful-SHA as a build
  # artifact), this caveat goes away.
  public_pipelines = toset([
    "pipeline",     # mockserver — top-level dispatcher (see note above)
    "ui",           # mockserver-ui — lint/test only
    "node",         # mockserver-node — lint/test only
    "python",       # mockserver-python — lint/test only
    "ruby",         # mockserver-ruby — lint/test only
    "maven-plugin", # mockserver-maven-plugin — build/test only
    "go",           # mockserver-go — lint/test only
    "dotnet",       # mockserver-dotnet — lint/test only
    "rust",         # mockserver-rust — lint/test only
    "php",          # mockserver-php — lint/test only
    "editors",      # mockserver-editors — lint/test only
  ])
}

resource "buildkite_pipeline" "pipeline" {
  for_each = local.pipelines

  name           = each.value.name
  description    = each.value.description
  repository     = local.repository
  default_branch = "master"
  emoji          = each.value.emoji
  visibility     = contains(local.public_pipelines, each.key) ? "PUBLIC" : "PRIVATE"

  # Assign every pipeline to the Default cluster (Buildkite deprecated
  # unclustered agents — see clusters.tf). Agents register with the cluster
  # token and only run jobs from pipelines in this cluster.
  cluster_id = data.buildkite_cluster.default.id

  # Cancel superseded in-progress builds on feature/PR branches (saves agent VMs
  # during rapid iteration), but NEVER on master: a master build that is canceled
  # mid-run reports as a misleading failure on the triggering pipeline, and the
  # commit goes untested. Long pipelines (container-tests ~20m, performance-test)
  # were the worst hit because, without this filter, every fresh master commit
  # canceled the previous still-running build before it could report. master builds
  # now always run to completion and report true pass/fail.
  #
  # SKIPPING of queued (not-yet-started) builds carries the SAME filter, and the
  # reasoning that once left it unfiltered was wrong for one pipeline. That
  # reasoning was: a skipped build reports "skipped" (neutral), not red, so
  # dropping an obsolete queued build is free. True when a build is a COMMIT
  # VALIDATION — a newer commit supersedes it and nothing is lost.
  #
  # It is false when the build IS THE MEASUREMENT. The daily performance run is
  # created at 04:00 and then QUEUES, because the perf queue is scale-to-zero with a
  # small agent cap. Any push to master during that wait created a newer build and the
  # queued daily was skipped before it ever started — so no measurement happened
  # that day, and perf-baseline-freshness.sh correctly reddened every subsequent
  # master build for reporting a producer that had not run.
  #
  # Proven on demand (2026-09-19) rather than inferred: on a throwaway branch,
  # build #1937 was left queued and #1938 created seconds later; #1937 flipped to
  # "skipped" immediately and never started. Observed for real on the daily —
  # #312 (schedule, master) was skipped 272s after creation, while #272, which had
  # a ~90 minute gap before the next build, ran.
  #
  # Filtering skip on !master therefore protects every scheduled/master build from
  # being dropped before it runs, while keeping the cost saving where it is safe:
  # feature and PR branches still skip their queued builds, which is what stops a
  # rapid-iteration branch hogging the perf agents.
  #
  # NARROWED TO THE PERF PIPELINE (2026-09-19, after G7). The first cut of this
  # applied the !master skip filter to every pipeline. A review judged that safe
  # because the other queues autoscale — but it did not consider the `trigger`
  # queue, which is HARD-CAPPED at 16 agents (4 instances x 4) and is the real
  # binding constraint. Each master commit spawns a dispatcher build that emits
  # one BLOCKING trigger step per affected child pipeline (trigger-pipeline.sh
  # polls in a sleep loop), and each of those holds a trigger agent for the whole
  # child build. Six concurrent dispatcher builds x 19 child pipelines is ~100
  # blocking waits against 16 slots; observed saturating for ~an hour and starving
  # the perf guard step, which also runs on `trigger`.
  #
  # Keeping master builds alive on EVERY pipeline would multiply exactly those
  # blocking waits during exactly the commit bursts that cause the saturation. So
  # the exemption is scoped to the pipeline that actually needs it: the perf
  # pipeline, whose scheduled daily IS the measurement and cannot be superseded.
  # Everywhere else, skipping a queued master build stays on — and now also serves
  # as the relief valve for trigger-queue pressure.
  cancel_intermediate_builds               = true
  cancel_intermediate_builds_branch_filter = "!master"
  skip_intermediate_builds                 = true
  skip_intermediate_builds_branch_filter   = each.key == "perf-test" ? "!master" : null

  steps = "steps:\n  - label: \":pipeline:\"\n    command: \"buildkite-agent pipeline upload ${each.value.file}\"\n"

  provider_settings = {
    trigger_mode          = each.value.trigger
    build_branches        = each.value.trigger == "code"
    build_pull_requests   = each.value.trigger == "code"
    build_tags            = false
    publish_commit_status = each.value.trigger == "code"
  }
}
