# Release toolchain images. NEVER BUILT: scripts/release/_lib.sh reads each FROM line by its
# stage name (pin_tool_image), and refuses any line that is not pinned by digest. Dependabot
# keeps the digests current in one grouped PR that is never auto-merged, because these images
# run only during a release. Dry-run the affected components (scripts/release/test-all.sh)
# before merging it. See docs/operations/release-process.md "Pinned Docker images".

# Keep the JDK at or above CI's JDK 17 (this digest is Temurin 17.0.20.1).
FROM maven:3.9.16-eclipse-temurin-17@sha256:1a352420f7aba21f5ad08df31bab55f74c013fb491f1ae8ab1dd7ff9ed698584 AS maven
FROM node:20-bookworm@sha256:8f693eaa7e0a8e71560c9a82b55fd54c2ae920a2ba5d2cde28bac7d1c01c9ba5 AS node
FROM ruby:3.2-bookworm@sha256:bf13a46a498fb4055d0e52495c351a2648c8bc8d41b473ee3f734041a74c7f56 AS ruby
# Community images (no official image exists); they receive the cosign key and GITHUB_TOKEN.
FROM alpine/helm:3.16.2@sha256:a19a2968fd672336d39771f6c899781424d725229148656dbc2a1e305003cdec AS helm
FROM maniator/gh:v2.62.0@sha256:fa810d026cd5eb356192845d301712f2c2c40d43810a8eb79b4ce93184951cdf AS gh
FROM python:3.12-slim-bookworm@sha256:935c3c5b12357a7e10caa9c1e6ca5692344d97cf229832ef584be2cec9fc03df AS python
FROM hashicorp/terraform:1.15@sha256:fd5debae63188975d6febc6aa5bd1a982a588f55e4a4ddb7de28be923f250456 AS terraform
# The highest `go` directive across our modules (mockserver-testcontainers/go needs 1.26), so no
# runtime GOTOOLCHAIN download.
FROM golang:1.26-bookworm@sha256:dc40a13b61304647ed7e5d581d246b86413dfc29d63c6d018310feb93cd98fc0 AS go
FROM rust:1-bookworm@sha256:e6f2cff35d0f309acf8433b4f05878ed77092602c5065cc81801177da0bb3a9c AS rust
FROM mcr.microsoft.com/dotnet/sdk:8.0@sha256:1b955f179b02df4f0eb5fadedaa898cbe44d620a64aab52eec72c8e91d1355b5 AS dotnet
