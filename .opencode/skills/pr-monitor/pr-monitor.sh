#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

MAX_ITERATIONS=60
SLEEP_INTERVAL=120
CHECK_ONCE=false
MERGE=false

usage() {
    cat <<EOF
Usage: $0 [OPTIONS] <pr_numbers|today>

Report the build status of dependency PRs. With --merge, also merge each
Dependabot/Snyk PR once its builds pass. Without it, nothing is merged.

Arguments:
  <pr_numbers>  Space-separated PR numbers to monitor (e.g., "2001 2005")
  today         Monitor all PRs created today

Options:
  --merge       Merge passing Dependabot/Snyk PRs (default: report only)
  --check-once  Check status once and exit (no loop)
  --max-time N  Maximum monitoring time in minutes (default: 120)
  --interval N  Check interval in seconds (default: 120)
  -h, --help    Show this help message

Examples:
  $0 today
  $0 2001 2005 2006
  $0 --check-once 2001
  $0 --merge 2001 2005
  $0 --max-time 60 --interval 60 today
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case $1 in
        --check-once)
            CHECK_ONCE=true
            shift
            ;;
        --merge)
            MERGE=true
            shift
            ;;
        --max-time)
            MAX_ITERATIONS=$(( $2 * 60 / SLEEP_INTERVAL ))
            shift 2
            ;;
        --interval)
            SLEEP_INTERVAL=$2
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            break
            ;;
    esac
done

if [ $# -eq 0 ]; then
    echo "Error: No PR numbers or 'today' specified"
    usage
fi

cd "$REPO_ROOT"

if [ "$1" = "today" ]; then
    TODAY=$(date +%Y-%m-%d)
    echo "Fetching PRs created on $TODAY..."
    PRS_TO_MONITOR=$(gh pr list --json number,createdAt --jq ".[] | select(.createdAt | startswith(\"$TODAY\")) | .number" | tr '\n' ' ')
    
    if [ -z "$PRS_TO_MONITOR" ]; then
        echo "No PRs found for today ($TODAY)"
        exit 0
    fi
else
    PRS_TO_MONITOR="$*"
fi

echo "========================================="
echo "PR Build Monitor"
echo "========================================="
echo "Repository: $(basename "$REPO_ROOT")"
echo "Monitoring PRs: $PRS_TO_MONITOR"
echo "Check interval: ${SLEEP_INTERVAL}s"
echo "Merge passing PRs: $MERGE"
if [ "$CHECK_ONCE" = false ]; then
    echo "Maximum runtime: $((MAX_ITERATIONS * SLEEP_INTERVAL / 60)) minutes"
fi
echo "========================================="
echo ""

GATE_REPO="${GH_REPO:-mock-server/mockserver-monorepo}"

# Mirrors the release-in-flight gate in .github/workflows/dependabot-auto-merge.yml:
# hold on an epoch younger than the backstop, a future value, a non-epoch value, or
# any read error other than "not found" (ambiguity holds).
release_hold_reason() {
    local flag max_hours age
    if ! flag=$(gh variable get RELEASE_IN_PROGRESS -R "$GATE_REPO" 2>&1); then
        [[ "$flag" == *"was not found"* ]] && return 1
        echo "cannot read RELEASE_IN_PROGRESS (${flag%%$'\n'*})"
        return 0
    fi
    [ -z "$flag" ] && return 1
    if ! [[ "$flag" =~ ^[0-9]{9,}$ ]]; then
        echo "RELEASE_IN_PROGRESS is not an epoch ('$flag')"
        return 0
    fi
    if ! max_hours=$(gh variable get RELEASE_GATE_MAX_AGE_HOURS -R "$GATE_REPO" 2>&1); then
        if [[ "$max_hours" != *"was not found"* ]]; then
            echo "cannot read RELEASE_GATE_MAX_AGE_HOURS (${max_hours%%$'\n'*})"
            return 0
        fi
    fi
    [[ "$max_hours" =~ ^[1-9][0-9]*$ ]] || max_hours=12
    age=$(( $(date -u +%s) - 10#$flag ))
    if [ "$age" -lt 0 ]; then
        echo "RELEASE_IN_PROGRESS is in the future"
        return 0
    elif [ "$age" -lt $(( max_hours * 3600 )) ]; then
        echo "release in progress (RELEASE_IN_PROGRESS set ${age}s ago)"
        return 0
    fi
    echo "  ⚠️  RELEASE_IN_PROGRESS is ${age}s old (>= ${max_hours}h): stranded flag, merging; ask the owner to clear it" >&2
    return 1
}

check_and_merge_prs() {
    local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[$timestamp] Checking PR status..."
    echo "-------------------------------------------"
    
    local all_done=true
    local prs_merged=0
    local prs_pending=0
    local prs_failing=0
    local prs_closed=0
    local prs_passing=0
    
    for pr_number in $PRS_TO_MONITOR; do
        local pr_data
        if ! pr_data=$(gh pr view "$pr_number" --json state,mergedAt,statusCheckRollup,title,author 2>&1); then
            echo "  PR #$pr_number: ⚠️  ERROR - Cannot fetch: $pr_data"
            all_done=false
            continue
        fi
        
        local state=$(echo "$pr_data" | jq -r '.state')
        local merged_at=$(echo "$pr_data" | jq -r '.mergedAt')
        local title=$(echo "$pr_data" | jq -r '.title')
        local author_login=$(echo "$pr_data" | jq -r '.author.login')
        
        # SAFETY CHECK: Only auto-merge bot PRs (Dependabot/Snyk)
        if [[ "$author_login" != "app/dependabot" && "$author_login" != "snyk-bot" ]]; then
            echo "  PR #$pr_number: ⚠️  SKIPPED - Not a bot PR (author: $author_login)"
            echo "             $title"
            echo "             Manual PRs are not auto-merged for safety"
            prs_closed=$((prs_closed + 1))
            continue
        fi
        
        if [ "$state" = "MERGED" ] || [ "$merged_at" != "null" ]; then
            echo "  PR #$pr_number: ✅ MERGED - $title"
            prs_merged=$((prs_merged + 1))
            continue
        fi
        
        if [ "$state" = "CLOSED" ]; then
            echo "  PR #$pr_number: ❌ CLOSED - $title"
            prs_closed=$((prs_closed + 1))
            continue
        fi
        
        local checks=$(echo "$pr_data" | jq -r '.statusCheckRollup')
        
        local buildkite_state=$(echo "$checks" | jq -r '[.[] | select(.__typename == "StatusContext" and .context == "buildkite/mockserver") | .state] | first // empty')
        local buildkite_url=$(echo "$checks" | jq -r '[.[] | select(.__typename == "StatusContext" and .context == "buildkite/mockserver") | .targetUrl] | first // empty')
        
        local actions_failed=$(echo "$checks" | jq '[.[] | select(.__typename == "CheckRun" and (.conclusion == "FAILURE" or .conclusion == "FAILED"))] | length')
        local actions_pending=$(echo "$checks" | jq '[.[] | select(.__typename == "CheckRun" and (.status == "IN_PROGRESS" or .status == "QUEUED" or .conclusion == ""))] | length')
        
        if [ -z "$buildkite_state" ]; then
            echo "  PR #$pr_number: ⏳ WAITING - No Buildkite status yet"
            echo "             $title"
            all_done=false
            prs_pending=$((prs_pending + 1))
        elif [ "$buildkite_state" = "SUCCESS" ] && [ "$actions_failed" = "0" ] && [ "$MERGE" = false ]; then
            echo "  PR #$pr_number: ✅ PASSING - not merged (report only; pass --merge to merge)"
            echo "             $title"
            prs_passing=$((prs_passing + 1))
        elif [ "$buildkite_state" = "SUCCESS" ] && [ "$actions_failed" = "0" ] && hold=$(release_hold_reason); then
            echo "  PR #$pr_number: ⏸️  HELD - $hold"
            echo "             $title"
            all_done=false
            prs_pending=$((prs_pending + 1))
        elif [ "$buildkite_state" = "SUCCESS" ] && [ "$actions_failed" = "0" ]; then
            echo "  PR #$pr_number: ✅ PASSING - Attempting to merge..."
            echo "             $title"
            
            if gh pr merge "$pr_number" --squash 2>&1; then
                echo "             🎉 MERGED"
                prs_merged=$((prs_merged + 1))
            else
                echo "             ⚠️  MERGE FAILED - Will retry"
                all_done=false
                prs_pending=$((prs_pending + 1))
            fi
        elif [ "$buildkite_state" = "FAILURE" ] || [ "$buildkite_state" = "FAILED" ]; then
            echo "  PR #$pr_number: ❌ FAILING - Buildkite: $buildkite_state"
            echo "             $title"
            echo "             Build: $buildkite_url"
            prs_failing=$((prs_failing + 1))
        elif [ "$buildkite_state" = "PENDING" ]; then
            local status_detail=""
            if [ "$actions_pending" -gt 0 ]; then
                status_detail=" (+ $actions_pending GitHub Actions running)"
            fi
            echo "  PR #$pr_number: ⏳ PENDING - Buildkite still running$status_detail"
            echo "             $title"
            all_done=false
            prs_pending=$((prs_pending + 1))
        else
            echo "  PR #$pr_number: ⏳ UNKNOWN - Buildkite: $buildkite_state"
            echo "             $title"
            all_done=false
            prs_pending=$((prs_pending + 1))
        fi
    done
    
    echo ""
    echo "Summary: ✅ $prs_merged merged | 🟢 $prs_passing passing (not merged) | ⏳ $prs_pending pending | ❌ $prs_failing failing | 🚫 $prs_closed closed"
    echo ""
    
    echo "$all_done"
}

if [ "$CHECK_ONCE" = true ]; then
    result=$(check_and_merge_prs)
    echo "$result" | sed '$d'
    exit 0
fi

iteration=0
while [ $iteration -lt $MAX_ITERATIONS ]; do
    iteration=$((iteration + 1))
    
    result=$(check_and_merge_prs)
    echo "$result" | sed '$d'
    all_done=$(echo "$result" | tail -1)
    
    if [ "$all_done" = "true" ]; then
        echo "========================================="
        echo "✅ All PRs processed!"
        echo "========================================="
        exit 0
    fi
    
    if [ $iteration -lt $MAX_ITERATIONS ]; then
        echo "Next check in ${SLEEP_INTERVAL}s... (Iteration $iteration/$MAX_ITERATIONS)"
        echo ""
        sleep $SLEEP_INTERVAL
    fi
done

echo "========================================="
echo "⏰ Maximum monitoring time reached"
echo "Some PRs may still be pending"
echo "========================================="
exit 1
