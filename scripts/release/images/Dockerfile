# Release toolchain images. NEVER BUILT: scripts/release/_lib.sh reads each FROM line by its
# stage name (pin_tool_image), and refuses any line that is not pinned by digest. Dependabot
# keeps the digests current in one grouped PR that is never auto-merged, because these images
# run only during a release. Dry-run the affected components (scripts/release/test-all.sh)
# before merging it. See docs/operations/release-process.md "Pinned Docker images".

# Keep the JDK at or above CI's JDK 17 (this digest is Temurin 17.0.20.1).
FROM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS maven
FROM node:25-bookworm@sha256:78839ac448c23517f8eab2e8f7943d9b4f73979eb7f8bed2c73dbf72ff869e7b AS node
FROM ruby:3.2-bookworm@sha256:bf13a46a498fb4055d0e52495c351a2648c8bc8d41b473ee3f734041a74c7f56 AS ruby
# Community images (no official image exists); they receive the cosign key and GITHUB_TOKEN.
FROM alpine/helm:4.3.1@sha256:e3f8d5c462d75470fbb1fce79ad5c20063657096d1f98c0fcea869e2600e2e5e AS helm
FROM maniator/gh:v2.103.0@sha256:0d1262882c7ba466c16b31619c334307b09bd407cc102987fff5d01ae7ac4cc0 AS gh
FROM python:3.15-slim-bookworm@sha256:96599df822d0e1c6bb700014a05c7f3c8cabc6e0f7ff3c4f9f69cee033b4b5d9 AS python
FROM hashicorp/terraform:1.16@sha256:c9c77f5b9820832cbc7b88d16df406ac6e75fee5a9077e5437db0c54e5ff33e3 AS terraform
# The highest `go` directive across our modules (mockserver-testcontainers/go needs 1.26), so no
# runtime GOTOOLCHAIN download.
FROM golang:1.26-bookworm@sha256:dc40a13b61304647ed7e5d581d246b86413dfc29d63c6d018310feb93cd98fc0 AS go
FROM rust:1-bookworm@sha256:e6f2cff35d0f309acf8433b4f05878ed77092602c5065cc81801177da0bb3a9c AS rust
FROM mcr.microsoft.com/dotnet/sdk:11.0@sha256:65f3c7c6a25a239ab3e6719f2896147e7b68242ff2d8818dd70498bde6ef8bea AS dotnet
