"""Unit tests for MockServerContainer configuration.

These tests verify container configuration WITHOUT starting Docker.
They test URL shaping, environment variable setup, and port configuration.
"""

import pytest

from testcontainers_mockserver import MockServerContainer
from testcontainers_mockserver.container import MOCKSERVER_PORT, _DEFAULT_TAG, _IMAGE_NAME


def exposed_ports(container):
    """Return the container's exposed ports as a set of ints.

    testcontainers stores exposed ports keyed by ``str(port)`` (4.15.0 typed
    ``ports`` as ``dict[str, Optional[int]]``), and the key may carry a protocol
    suffix such as ``"1080/tcp"``. Normalising here keeps these assertions
    meaningful across both key styles rather than silently passing whenever the
    key type changes.
    """
    return {int(str(port).split("/")[0]) for port in container.ports}


class TestDefaultConfiguration:
    """Tests for default container configuration (no Docker needed)."""

    def test_default_image(self):
        container = MockServerContainer()
        assert container.image == f"{_IMAGE_NAME}:{_DEFAULT_TAG}"

    def test_default_port_constant(self):
        assert MOCKSERVER_PORT == 1080

    def test_default_exposes_port_1080(self):
        container = MockServerContainer()
        # The ports dict keys are the container ports
        assert MOCKSERVER_PORT in exposed_ports(container)

    def test_default_sets_server_port_env(self):
        container = MockServerContainer()
        assert container.env["SERVER_PORT"] == "1080"

    def test_custom_image(self):
        container = MockServerContainer(image="mockserver/mockserver:latest")
        assert container.image == "mockserver/mockserver:latest"

    def test_custom_port(self):
        container = MockServerContainer(port=9090)
        assert 9090 in exposed_ports(container)
        assert container.env["SERVER_PORT"] == "9090"


class TestWithServerPort:
    """Tests for with_server_port fluent method."""

    def test_sets_env_and_port(self):
        container = MockServerContainer()
        result = container.with_server_port(9090)

        assert result is container  # fluent return
        assert container.env["SERVER_PORT"] == "9090"
        assert 9090 in exposed_ports(container)

    def test_replaces_default_port(self):
        container = MockServerContainer()
        container.with_server_port(9090)

        # Default port 1080 should no longer be exposed
        assert MOCKSERVER_PORT not in exposed_ports(container)
        assert 9090 in exposed_ports(container)


class TestWithLogLevel:
    """Tests for with_log_level fluent method."""

    def test_sets_log_level_env(self):
        container = MockServerContainer()
        result = container.with_log_level("DEBUG")

        assert result is container
        assert container.env["MOCKSERVER_LOG_LEVEL"] == "DEBUG"


class TestWithProperty:
    """Tests for with_property fluent method."""

    def test_sets_arbitrary_env(self):
        container = MockServerContainer()
        result = container.with_property("MOCKSERVER_MAX_EXPECTATIONS", "500")

        assert result is container
        assert container.env["MOCKSERVER_MAX_EXPECTATIONS"] == "500"


class TestWithInitializationJson:
    """Tests for with_initialization_json fluent method."""

    def test_sets_initialization_json_path_env(self):
        container = MockServerContainer()
        result = container.with_initialization_json("/config/init.json")

        assert result is container
        assert container.env["MOCKSERVER_INITIALIZATION_JSON_PATH"] == "/config/init.json"


class TestFluentChaining:
    """Tests for method chaining across multiple fluent helpers."""

    def test_chain_multiple_methods(self):
        container = MockServerContainer()
        result = (
            container
            .with_log_level("WARN")
            .with_server_port(8080)
            .with_property("MOCKSERVER_MAX_EXPECTATIONS", "100")
        )

        assert result is container
        assert container.env["MOCKSERVER_LOG_LEVEL"] == "WARN"
        assert container.env["SERVER_PORT"] == "8080"
        assert container.env["MOCKSERVER_MAX_EXPECTATIONS"] == "100"
        assert 8080 in exposed_ports(container)


class TestUrlShaping:
    """Tests for URL/host/port accessors.

    These test the URL construction logic by mocking the underlying testcontainers
    methods that require a running container.
    """

    def test_get_url_format(self):
        """Verify get_url produces the correct format when host and port are known."""
        container = MockServerContainer()
        # Monkey-patch the methods that need Docker
        container.get_container_host_ip = lambda: "127.0.0.1"
        container.get_exposed_port = lambda port: 49152

        url = container.get_url()
        assert url == "http://127.0.0.1:49152"

    def test_get_secure_url_format(self):
        """Verify get_secure_url uses https scheme."""
        container = MockServerContainer()
        container.get_container_host_ip = lambda: "localhost"
        container.get_exposed_port = lambda port: 55000

        url = container.get_secure_url()
        assert url == "https://localhost:55000"

    def test_get_host_delegates(self):
        """Verify get_host delegates to get_container_host_ip."""
        container = MockServerContainer()
        container.get_container_host_ip = lambda: "192.168.1.100"

        assert container.get_host() == "192.168.1.100"

    def test_get_port_delegates(self):
        """Verify get_port delegates to get_exposed_port with the configured port."""
        container = MockServerContainer()
        container.get_exposed_port = lambda port: 32768

        assert container.get_port() == 32768

    def test_get_port_respects_custom_port(self):
        """Verify get_port uses the custom server port when configured."""
        container = MockServerContainer(port=9090)

        called_with = []
        container.get_exposed_port = lambda port: (called_with.append(port), 32768)[1]

        container.get_port()
        assert called_with == [9090]

    def test_get_url_with_custom_port(self):
        """Verify get_url works correctly after with_server_port."""
        container = MockServerContainer()
        container.with_server_port(9090)
        container.get_container_host_ip = lambda: "localhost"

        called_with = []
        container.get_exposed_port = lambda port: (called_with.append(port), 45000)[1]

        url = container.get_url()
        assert url == "http://localhost:45000"
        assert called_with == [9090]


class TestVersionDerivation:
    """Tests that verify the default image tag is derived, not hard-pinned."""

    def test_default_tag_derives_from_client_or_latest(self):
        """The default tag derives from the client version, or falls back to latest."""
        # Either 'mockserver-<version>' (client installed) or 'latest' (fallback).
        assert _DEFAULT_TAG == "latest" or _DEFAULT_TAG.startswith("mockserver-")

    def test_default_tag_helper_falls_back_to_latest(self, monkeypatch):
        """When the client package is absent, the tag falls back to 'latest'."""
        from importlib import metadata

        from testcontainers_mockserver import container as container_module

        def _raise(_name):
            raise metadata.PackageNotFoundError

        monkeypatch.setattr(container_module.metadata, "version", _raise)
        assert container_module._default_tag() == "latest"


class TestGetClient:
    """Tests for the get_client convenience helper."""

    def test_get_client_returns_configured_client(self):
        """get_client returns a MockServerClient pointed at the mapped host/port."""
        from mockserver import MockServerClient

        container = MockServerContainer()
        container.get_container_host_ip = lambda: "127.0.0.1"
        container.get_exposed_port = lambda port: 49152

        client = container.get_client()
        assert isinstance(client, MockServerClient)

    def test_get_client_is_cached(self):
        """get_client caches the client across calls."""
        container = MockServerContainer()
        container.get_container_host_ip = lambda: "127.0.0.1"
        container.get_exposed_port = lambda port: 49152

        assert container.get_client() is container.get_client()
