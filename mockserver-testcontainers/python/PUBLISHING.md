# Publishing testcontainers-mockserver to PyPI

## Prerequisites

- Python >= 3.9
- `build` and `twine` packages installed
- PyPI API token stored in AWS Secrets Manager at `mockserver-build/pypi`

## Build

```bash
cd mockserver-testcontainers/python
python -m build
```

This produces `dist/testcontainers_mockserver-<version>.tar.gz` and
`dist/testcontainers_mockserver-<version>-py3-none-any.whl`.

## Publish (non-interactive)

```bash
# Retrieve the PyPI token from Secrets Manager
PYPI_TOKEN=$(aws secretsmanager get-secret-value \
  --profile mockserver-build \
  --secret-id mockserver-build/pypi \
  --query SecretString --output text)

# Upload to PyPI
twine upload dist/* --username __token__ --password "$PYPI_TOKEN"
```

## Liveness verification

After publishing, verify the package is live:

```bash
pip install --no-cache-dir testcontainers-mockserver==9.2.0
python -c "from testcontainers_mockserver import MockServerContainer; print('OK')"
```

Or via the PyPI JSON API:

```bash
curl -sf https://pypi.org/pypi/testcontainers-mockserver/json | python -c "import sys,json; print(json.load(sys.stdin)['info']['version'])"
```
