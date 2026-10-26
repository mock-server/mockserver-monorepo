# MockServer Testcontainers Module for Go

A [Testcontainers](https://golang.testcontainers.org/) module that starts a
[MockServer](https://www.mock-server.com) Docker container for integration testing in Go.

## Installation

```bash
go get github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go/v9
```

## Usage

```go
package example_test

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"strings"

	mockserver "github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go/v9"
)

func Example() {
	ctx := context.Background()

	// Start a MockServer container
	ctr, err := mockserver.Run(ctx, mockserver.DefaultImage)
	if err != nil {
		log.Fatal(err)
	}
	defer ctr.Terminate(ctx)

	// Get the base URL
	url, err := ctr.URL(ctx)
	if err != nil {
		log.Fatal(err)
	}

	// Create an expectation
	expectation := `{
		"httpRequest": {"method": "GET", "path": "/hello"},
		"httpResponse": {"statusCode": 200, "body": "world"}
	}`
	req, _ := http.NewRequestWithContext(ctx, http.MethodPut, url+"/mockserver/expectation",
		strings.NewReader(expectation))
	req.Header.Set("Content-Type", "application/json")
	http.DefaultClient.Do(req)

	// Call the mocked endpoint
	resp, _ := http.Get(url + "/hello")
	fmt.Printf("Status: %d\n", resp.StatusCode)
	// Output: Status: 200
}
```

## API

### `Run(ctx, image, opts...) (*MockServerContainer, error)`

Creates and starts a MockServer container. Waits for the `/mockserver/status`
endpoint to respond with HTTP 200 before returning.

- `image` — Docker image (e.g. `"mockserver/mockserver:mockserver-9.2.0"`)
- `opts` — optional `testcontainers.ContainerCustomizer` values

### `MockServerContainer.URL(ctx) (string, error)`

Returns the HTTP base URL (e.g. `"http://localhost:32769"`).

### `MockServerContainer.ServerPort(ctx) (int, error)`

Returns the mapped host port for the MockServer container port 1080.

### Constants and variables

- `DefaultPort` — `"1080/tcp"`
- `DefaultImageName` — `"mockserver/mockserver"`
- `DefaultImage` — a package variable derived at init from the `mockserver-client-go`
  dependency version in the build info (`mockserver/mockserver:mockserver-<version>`),
  falling back to `:latest` when the version cannot be resolved.

### `MockServerContainer.Client(ctx) (*mockserverclient.Client, error)`

Returns a [`mockserver-client-go`](https://pkg.go.dev/github.com/mock-server/mockserver-monorepo/mockserver-client-go/v9)
control-plane client already pointed at the container's mapped host and port — the Go
equivalent of the Java module's `getClient()` helper:

```go
import mockserverclient "github.com/mock-server/mockserver-monorepo/mockserver-client-go/v9"

client, err := ctr.Client(ctx)
if err != nil {
	log.Fatal(err)
}

_, err = client.When(
	mockserverclient.Request().Method("GET").Path("/hello"),
).Respond(
	mockserverclient.Response().StatusCode(200).Body("world"),
)
```

The client is bundled via the `mockserver-client-go/vN` module (N = the release major). The `/vN` Semantic Import
Versioning suffix on that module's path is what lets this module depend on it as a normal
`require`, so downstream `go get` of this module resolves the published client cleanly.

## Build and Test

```bash
# Unit tests (no Docker required)
go test -run 'TestURL|TestDefault' ./...

# All tests (requires Docker)
go test -v ./...

# Vet
go vet ./...
```

## Requirements

- Go 1.22+
- Docker (for integration tests)
