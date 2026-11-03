#!/usr/bin/env bash
# The wire-contract paths: a change to any of them makes generate-pipeline.sh run ALL seven
# client pipelines. Other server changes run none; the nightly FORCE_ALL_CLIENTS build
# (terraform/buildkite-pipelines/pipelines.tf) catches what this list misses. The reason for
# each path: docs/infrastructure/ci-cd.md, "Client Conformance Triggering".
# Sourced by generate-pipeline.sh. Tests: .buildkite/scripts/test/generate-pipeline-clients-test.sh

_CORE='mockserver/mockserver-core/src/main/java/org/mockserver'
_NETTY='mockserver/mockserver-netty/src/main/java/org/mockserver'
_CODEC='Body[A-Za-z]*|NettyHttpToMockServerHttp[A-Za-z]*|MockServerHttpToNettyHttp[A-Za-z]*|ExpandedParameterDecoder|PathParametersDecoder|MultipartFormDataDecoder|JsonSchemaBodyDecoder'
WIRE_CHANGES_REGEX="^(test-fixtures/"
WIRE_CHANGES_REGEX+="|mockserver/mockserver-core/src/main/resources/"
WIRE_CHANGES_REGEX+="|${_CORE}/(model|mock|validator|mappers|matchers|verify|closurecallback|authentication|oidc|socket|grpc|graphql|responsewriter|templates|llm|log|slo|metrics|load|openapi|imports|cors)/"
WIRE_CHANGES_REGEX+="|${_CORE}/serialization/([A-Za-z0-9]+\.java$|model/|serializers/|deserializers/|har/)"
WIRE_CHANGES_REGEX+="|${_CORE}/codec/(${_CODEC})\.java$"
WIRE_CHANGES_REGEX+="|${_NETTY}/netty/(HttpRequestHandler|OpenAPISpecHandler|unification/PortUnificationHandler)\.java$"
WIRE_CHANGES_REGEX+="|${_NETTY}/netty/(websocketregistry|dns|grpc|responsewriter)/"
WIRE_CHANGES_REGEX+="|${_NETTY}/(cli|lifecycle)/"
WIRE_CHANGES_REGEX+="|mockserver/mockserver-core/src/test/resources/grpc/"
WIRE_CHANGES_REGEX+="|mockserver/mockserver-netty/src/test/resources/org/mockserver/netty/integration/tls/"
WIRE_CHANGES_REGEX+="|mockserver/mockserver-netty-docker/"
WIRE_CHANGES_REGEX+="|docker/local/|docker/ensure-ca-bundle\.sh$"
WIRE_CHANGES_REGEX+="|\.buildkite/scripts/(build-local-mockserver-image|with-mockserver|run-in-docker)\.sh$"
WIRE_CHANGES_REGEX+=")"
unset _CORE _NETTY _CODEC

# Changed paths on stdin -> the ones that run every client pipeline.
client_wire_changes() { grep -E -- "$WIRE_CHANGES_REGEX" || true; }
