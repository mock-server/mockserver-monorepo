# Python — Testcontainers Cross-Link Draft

## Publish Gate

**Do not post until `testcontainers-mockserver` is live on PyPI.**

Verify:
```bash
pip install testcontainers-mockserver==9.2.0
python -c "from testcontainers_mockserver import MockServerContainer; print('OK')"
# or: curl -sf https://pypi.org/pypi/testcontainers-mockserver/json | python -c "import sys,json; print(json.load(sys.stdin)['info']['version'])"
```

---

## Target

**Repository:** https://github.com/testcontainers/testcontainers-python

The testcontainers-python library ships community modules under
`testcontainers/modules/`. A `mockserver` module may already exist there. The PR either:
- adds a note in the existing module pointing at the official MockServer-maintained
  package on PyPI, or
- adds a new entry to the modules documentation/README.

File most likely to PR against: `README.md` or `modules/mockserver/` (if present),
or raise a GitHub issue if the maintainers prefer issue-driven additions.

**Fallback:** open an issue at https://github.com/testcontainers/testcontainers-python/issues.

---

## PR Title

```
docs: add official MockServer module — testcontainers-mockserver (PyPI)
```

---

## PR / Issue Body

```markdown
## Summary

MockServer now ships an officially maintained Testcontainers module for Python:
[`testcontainers-mockserver`](https://pypi.org/project/testcontainers-mockserver/).

The module is maintained by the MockServer project and tracks each MockServer release.
It wraps `testcontainers >= 4.0.0` and supports Python 3.9–3.14.

## Install

```bash
pip install testcontainers-mockserver
```

## Quick start

```python
from testcontainers_mockserver import MockServerContainer
import requests

with MockServerContainer() as mockserver:
    url = mockserver.get_url()  # e.g. "http://localhost:49152"

    requests.put(f"{url}/mockserver/expectation", json={
        "httpRequest": {"method": "GET", "path": "/hello"},
        "httpResponse": {"statusCode": 200, "body": "world"},
    })

    resp = requests.get(f"{url}/hello")
    assert resp.text == "world"
```

## Links

- PyPI: https://pypi.org/project/testcontainers-mockserver/
- Source: https://github.com/mock-server/mockserver/tree/master/mockserver-testcontainers/python
- MockServer docs: https://www.mock-server.com
```

---

## Notes for the Submitter

- Package name on PyPI: `testcontainers-mockserver`; import name: `testcontainers_mockserver`.
- Python >= 3.9; testcontainers >= 4.0.0.
- If testcontainers-python already ships a `mockserver` community module, the PR should
  add a notice pointing to the official maintained package instead.
- File source: `mockserver-testcontainers/python/pyproject.toml` (name, version, deps verified).
