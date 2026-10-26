# Setup MockServer — GitHub Action

A composite action that starts a [MockServer](https://www.mock-server.com) instance (HTTP(S) mock
server & proxy) for a CI job and waits until it is ready. It runs the official
`mockserver/mockserver` Docker image and polls the control-plane status endpoint.

## Usage

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@df4cb1c069e1874edd31b4311f1884172cec0e10  # v6.0.3

      - name: Start MockServer
        id: mockserver
        uses: mock-server/mockserver-monorepo/.github/actions/setup-mockserver@master
        with:
          version: latest      # or a release tag e.g. mockserver-9.2.0
          port: '1080'

      - name: Run tests against MockServer
        run: |
          curl -sf -X PUT "${{ steps.mockserver.outputs.url }}/mockserver/expectation" \
            -H 'Content-Type: application/json' \
            -d '{"httpRequest":{"path":"/hello"},"httpResponse":{"body":"Hello World"}}'
          test "$(curl -s ${{ steps.mockserver.outputs.url }}/hello)" = "Hello World"
```

## Inputs

| Input | Default | Description |
|-------|---------|-------------|
| `version` | `latest` | MockServer Docker image tag (e.g. `latest`, `mockserver-9.2.0`). |
| `port` | `1080` | Host port to expose MockServer on (the container listens on 1080 internally). |
| `container-name` | `mockserver` | Name for the started container. |
| `log-level` | `INFO` | MockServer log level (`INFO`, `WARN`, `DEBUG`, `TRACE`). |
| `args` | `''` | Extra arguments appended to the MockServer command. **Do not populate from untrusted input** (e.g. PR titles) — values are word-split into the docker command. |
| `startup-timeout` | `60` | Max seconds to wait for readiness before failing. |

## Outputs

| Output | Description |
|--------|-------------|
| `url` | Base URL of the running MockServer (`http://localhost:<port>`). |

## Notes

- Runs on Linux runners (Docker pre-installed). The container is started with `--rm`; the runner is
  torn down at job end, so no explicit cleanup step is required.
- **Marketplace listing (not yet published):** this action is **not** currently on the GitHub
  Marketplace. It works today only via the full monorepo path shown above
  (`…/.github/actions/setup-mockserver@<ref>`). Marketplace requires an action's `action.yml` at the
  **root** of a repository, so listing it would mean mirroring this directory to a dedicated
  `mock-server/setup-mockserver` repo (a future step; the action itself would be unchanged).
