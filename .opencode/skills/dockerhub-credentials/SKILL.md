---
name: dockerhub-credentials
description: >
  Manages Docker Hub credentials for the MockServer project. Guides the user through
  creating a Docker Hub Personal Access Token, validates it, and stores it in AWS
  Secrets Manager for use by CI pipelines. Use when the user says "docker hub credentials",
  "docker hub token", "set up docker push", "configure docker hub", "rotate docker token",
  or needs to store Docker Hub credentials in AWS Secrets Manager.
---

# Store Docker Hub Credentials in AWS Secrets Manager

This skill guides you through creating (or rotating) Docker Hub credentials and storing
them in AWS Secrets Manager (`mockserver-build/dockerhub`) for use by Buildkite pipelines.

## Prerequisites

- AWS CLI installed and configured with the `mockserver-build` SSO profile
- Authenticated to AWS: `aws sso login --profile mockserver-build`
- Docker Hub account with push access to the `mockserver/mockserver` repository
- Terraform resources deployed (the `aws_secretsmanager_secret.dockerhub` resource
  in `terraform/buildkite-agents/build-secrets.tf` must exist)

## Step 1: Check for Local Docker Credentials (Optional)

Check whether Docker Hub credentials already exist locally, without printing them:

```bash
# Report only whether a Docker Hub entry exists and for which user. Never print the
# Secret field.
STORE="$(python3 -c "import json,os; print(json.load(open(os.path.expanduser('~/.docker/config.json'))).get('credsStore',''))")"
[ -n "$STORE" ] && echo "https://index.docker.io/v1/" | "docker-credential-$STORE" get 2>/dev/null \
  | python3 -c "import sys,json; print('found for user:', json.load(sys.stdin).get('Username'))" \
  || echo "no Docker Hub entry"
```

This is information only: it tells you which Docker Hub user is signed in locally. Docker
Desktop entries are often session tokens rather than reusable PATs, so always create a
dedicated PAT in Step 2.

## Step 2: Create a Docker Hub Personal Access Token

1. Instruct the user to navigate to Docker Hub:
   - URL: `https://app.docker.com/settings/personal-access-tokens`
   - Or: Docker Hub → Avatar → Account Settings → Personal Access Tokens
2. Click **Generate New Token**
3. Settings:
   - **Description**: `mockserver-ci` (or similar)
   - **Permissions**: **Read & Write** (needed to push images)
   - **Expiration**: Choose based on rotation policy (recommended: 1 year)
4. Copy the generated token (format: `dckr_pat_...`)

**Never ask the user to paste the token into the chat**, and never put it on a command
line: a pasted token lands in the conversation transcript, and an argument shows in
process lists and shell history.

## Step 3: Validate and Store (the user runs this)

Ask the user to run this block **in their own terminal**, not through the `!` prefix
(which has no TTY for the hidden prompt and copies output into the conversation). It works
in both bash and zsh. It prompts for the token without echoing it, validates it over stdin,
and passes it to AWS through a private temporary file that is removed on exit or Ctrl-C, so
the token never appears in the chat, in `argv` or in shell history:

```bash
(
  umask 077
  SECRET_FILE=""
  trap 'stty echo 2>/dev/null; [ -n "$SECRET_FILE" ] && rm -f "$SECRET_FILE"' EXIT
  trap 'exit 130' INT TERM HUP
  printf 'Docker Hub username: '; IFS= read -r DH_USER
  printf 'Docker Hub PAT: '; stty -echo; IFS= read -r DH_TOKEN; stty echo; echo
  if printf '%s' "$DH_TOKEN" | docker login --username "$DH_USER" --password-stdin >/dev/null; then
    docker logout >/dev/null
    SECRET_FILE="$(mktemp)"
    printf '%s' "$DH_TOKEN" | DH_USER="$DH_USER" python3 -c \
      'import json,os,sys; print(json.dumps({"username": os.environ["DH_USER"], "token": sys.stdin.read()}))' \
      >| "$SECRET_FILE" &&
    aws secretsmanager put-secret-value \
      --secret-id mockserver-build/dockerhub \
      --secret-string "file://$SECRET_FILE" \
      --profile mockserver-build --region eu-west-2 \
      --query VersionId --output text
  else
    echo "docker login failed: check the username and token" >&2
  fi
)
```

The block logs out of Docker Hub after validating, which also signs the user out of any
Docker Hub session they already had locally. If login fails, ask the user to check the token and run the block again. The subshell
keeps `umask` and the variables out of their shell.

## Step 4: Verify Storage

Confirm the secret was stored correctly by checking the JSON structure (not the token value):

```bash
aws secretsmanager get-secret-value \
    --secret-id mockserver-build/dockerhub \
    --profile mockserver-build \
    --region eu-west-2 \
    --query 'SecretString' \
    --output text | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'✓ Secret contains username: {\"username\" in d}'); print(f'✓ Secret contains token: {\"token\" in d}'); print(f'✓ Username: {d.get(\"username\", \"MISSING\")}'); print(f'✓ Token length: {len(d.get(\"token\", \"\"))} chars')"
```

**SECURITY:** Never print any part of the token value (not even the first N characters). Verify by checking the JSON structure and token length only.

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| `aws secretsmanager put-secret-value` fails with "not found" | Terraform not applied | Run `terraform apply` in `terraform/buildkite-agents/` |
| `docker login` fails with 401 | Token expired or invalid | Create a new PAT in Docker Hub |
| AWS CLI fails with expired SSO token | SSO session expired | Run `aws sso login --profile mockserver-build` |
| `ResourceNotFoundException` | Secret doesn't exist yet | Ensure `terraform apply` has been run for `build-secrets.tf` |

## Security Notes

- **Never** commit Docker Hub tokens to the repository
- **Never** log the full token value in CI output
- The secret in AWS Secrets Manager is encrypted at rest using the default KMS key
- Access is scoped: only the Buildkite agent EC2 instance role can read the secret (via `buildkite-read-dockerhub-secret` IAM policy)
- Rotate the token if it may have been exposed
