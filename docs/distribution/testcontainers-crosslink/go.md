# Go — Testcontainers Cross-Link Draft

## Publish Gate

**Do not post until the Go module is indexed on pkg.go.dev.**

The module is published automatically when a semver tag of the form
`mockserver-testcontainers/go/vX.Y.Z` is pushed to the monorepo. Verify indexing:

```bash
go list -m -versions github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go/v9
# or visit: https://pkg.go.dev/github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go/v9
```

The Go proxy may take a few minutes to index after the tag is pushed.

**Note on module path:** the module currently lives in the monorepo at
`github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go/v9`. The
`PUBLISHING.md` notes a future split to a dedicated repo
(`github.com/mock-server/mockserver-testcontainers-go`). Post the PR against whichever
path is live — do not post until the path resolves via `go get`.

---

## Target

**Repository:** https://github.com/testcontainers/testcontainers-go

The testcontainers-go project ships community modules under `modules/`. A `mockserver`
module may exist there. The PR either:
- adds a redirect notice in the existing module pointing at the official maintained
  module, or
- adds a new entry to the `modules/` docs listing.

File most likely to PR against: `modules/mockserver/` (if present) or the module
listing in `README.md` / `docs/modules/`.

**Fallback:** open an issue at https://github.com/testcontainers/testcontainers-go/issues.

---

## PR Title

```
docs: add official MockServer module for Go
```

---

## PR / Issue Body

```markdown
## Summary

MockServer now ships an officially maintained Testcontainers module for Go:
[`github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go/v9`](https://pkg.go.dev/github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go/v9).

The module is maintained by the MockServer project, wraps `testcontainers-go` v0.36+,
and tracks each MockServer release.

## Install

```bash
go get github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go/v9
```

## Quick start

```go
import (
    "context"
    "net/http"
    "strings"

    mockserver "github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go/v9"
)

ctx := context.Background()

ctr, err := mockserver.Run(ctx, mockserver.DefaultImage)
if err != nil { /* handle */ }
defer ctr.Terminate(ctx)

url, _ := ctr.URL(ctx) // e.g. http://localhost:32769

expectation := `{"httpRequest":{"method":"GET","path":"/hello"},"httpResponse":{"statusCode":200,"body":"world"}}`
req, _ := http.NewRequestWithContext(ctx, http.MethodPut, url+"/mockserver/expectation", strings.NewReader(expectation))
req.Header.Set("Content-Type", "application/json")
http.DefaultClient.Do(req)

resp, _ := http.Get(url + "/hello")
// resp.StatusCode == 200
```

## Links

- pkg.go.dev: https://pkg.go.dev/github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go/v9
- Source: https://github.com/mock-server/mockserver/tree/master/mockserver-testcontainers/go
- MockServer docs: https://www.mock-server.com
```

---

## Notes for the Submitter

- Module path: `github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go/v9`
- Go 1.26+; testcontainers-go v0.36+.
- Entry point: `mockserver.Run(ctx, image, opts...)` returns `*MockServerContainer`;
  `ctr.URL(ctx)` returns the HTTP base URL.
- Published via a git tag `mockserver-testcontainers/go/vX.Y.Z` on the monorepo — the
  Go proxy indexes it automatically.
- File source: `mockserver-testcontainers/go/go.mod` (module path, Go version verified).
