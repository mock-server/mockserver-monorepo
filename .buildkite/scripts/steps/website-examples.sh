#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Runs one language's website examples through its client (each block against
# a local capture server, no MockServer) and compares what they send with the
# REST API tab. The check is Ruby, so Ruby is added to the language's image.
lang="${1:?usage: website-examples.sh python|go|csharp|rust|php}"
check="cd /build/mockserver-client-ruby && ruby spec/website_examples/check_website_examples.rb --lang $lang"
debian_ruby="apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ruby >/dev/null"
cache=()
case "$lang" in
  python) image=python:3.12; cache=(--cache pip)
          setup="$debian_ruby && pip install -q requests -e /build/mockserver-client-python" ;;
  go)     image=golang:1.23; cache=(--cache go); setup="$debian_ruby" ;;
  csharp) image=mcr.microsoft.com/dotnet/sdk:10.0; cache=(--cache nuget); setup="$debian_ruby" ;;
  rust)   image=rust:1; cache=(--cache cargo); setup="$debian_ruby" ;;
  php)    image=composer:2@sha256:0c8e663f14c94d07b3c45b175c51f5a14718508f21fe4dd7e54f09488dd0ed71
          setup="apk add --no-cache ruby >/dev/null && cd /build/mockserver-client-php && composer install --no-interaction --prefer-dist --quiet" ;;
  *) echo "unknown language: $lang" >&2; exit 2 ;;
esac

exec "$SCRIPT_DIR/../run-in-docker.sh" -i "$image" ${cache[@]+"${cache[@]}"} \
  -e WEBSITE_EXAMPLES_WORK_DIR=/tmp/website-examples -e LANG=C.UTF-8 -- sh -ec "$setup && $check"
