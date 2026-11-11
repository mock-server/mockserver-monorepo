# Publishing MockServerClient to NuGet

## Package

- **Package ID:** `MockServerClient` (the assembly and C# namespace remain
  `MockServer.Client`; only the NuGet id differs, because the dotted
  `MockServer.Client` id is owned by an unrelated third party)
- **Registry:** [nuget.org](https://www.nuget.org/packages/MockServerClient)
- **Status:** the `MockServerClient` id is **claimed** (first published as
  `8.0.0-alpha.1` on 2026-06-11 to reserve the name); the release pipeline publishes <!-- version-ref:keep -->
  the stable release version on top.
- **Secret:** `mockserver-release/nuget` (AWS Secrets Manager, JSON key `api_key` — the
  release scripts read it via `load_secret … api_key`; the `NUGET_API_KEY` below is just the
  shell env-var name the manual snippet assigns it to). Created out of band; referenced in
  Terraform via a data source.

## Non-interactive publish command

```bash
# 1. Build and pack (PackageId in the .csproj produces MockServerClient.*.nupkg)
cd mockserver-client-dotnet
dotnet pack src/MockServer.Client/MockServer.Client.csproj -c Release -o ./artifacts

# 2. Push to NuGet (requires NUGET_API_KEY environment variable)
dotnet nuget push ./artifacts/MockServerClient.*.nupkg \
  --api-key "$NUGET_API_KEY" \
  --source https://api.nuget.org/v3/index.json \
  --skip-duplicate
```

## Retrieving the secret

```bash
NUGET_API_KEY=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-release/nuget \
  --query SecretString --output text \
  --profile mockserver-build --region eu-west-2 | jq -r '.api_key')
```

## Version management

The package version is set in `src/MockServer.Client/MockServer.Client.csproj` in the `<Version>` property.
It must match the MockServer release version (read from `mockserver/pom.xml`, strip `-SNAPSHOT`).

## Lock file

`src/MockServer.Client/packages.lock.json` pins every package the published project restores, with its hash. The release
(`scripts/release/components/client-dotnet.sh`) and CI restore that project with `--locked-mode`, so a
`PackageReference` change without a matching lock file fails CI. After changing a
`PackageReference`, regenerate it from this directory with
`dotnet restore src/MockServer.Client/MockServer.Client.csproj --force-evaluate` and commit it.

## Liveness check

After publishing, verify the package is live:

```bash
dotnet nuget list source --format short | grep -q nuget.org && \
  curl -sf "https://api.nuget.org/v3-flatcontainer/mockserverclient/index.json" | \
  python3 -c "import sys,json; versions=json.load(sys.stdin)['versions']; print(versions[-1])"
```

## Eventual module split

When this package is eventually split to its own repository (`github.com/mock-server/mockserver-client-dotnet`),
update the `RepositoryUrl` in the `.csproj` and the NuGet source link configuration accordingly.
