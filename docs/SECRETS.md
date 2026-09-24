# Secrets

No secret values are stored in this repo. Each one lives in a store that only the repo owner can read, and the code refers to it by name.

## Where each secret lives

| Secret | Used by | Local dev | GitHub Actions | AKS cluster |
|---|---|---|---|---|
| `DEEPSEEK_API_KEY` | agent-service, control-plane | `.env` or shell export | not needed | `app-secrets` Secret |
| `TAVILY_API_KEY` | retrieval-service | `.env` or shell export | not needed | `secret-ref` Secret |
| `SEARXNG_SECRET` | searxng | `.env` (compose has a local-only fallback) | not needed | `app-secrets` Secret, key `SEARXNG_SECRET` |
| Postgres password (`SPRING_DATASOURCE_PASSWORD`) | control-plane, agent-service | not needed: falls back to `research`, the local container's password | not needed | `app-secrets` Secret |
| Redis access key | retrieval-service | none (local Redis has no password) | not needed | `secret-ref` Secret |
| `EVENTHUBS_CONNECTION_STRING` | agent-service, control-plane | not needed (Redpanda) | not needed | `app-secrets` Secret |
| `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID` | deploy workflows | not needed | repo Secrets | not needed |
| `SONAR_TOKEN` | `backend-pr-validation` (SonarQube Cloud scan) | not needed | repo Secret (the scan skips itself without it) | not needed |

The Azure login in `.github/workflows/_build-deploy.yml` uses OIDC: GitHub hands Azure a short-lived signed token and Azure checks it against a federated credential. There is no Azure password or client secret anywhere, including in GitHub.

## Local setup

```bash
cp .env.example .env    # .env is gitignored
# fill in the values, then:
docker compose up -d
```

`docker compose` reads `.env` by itself. For `mvn spring-boot:run`, export the same variables in your shell; Spring maps `DEEPSEEK_API_KEY` and `TAVILY_API_KEY` onto the config keys that reference them.

## Cluster setup

`app-secrets` and `secret-ref` are created by hand in the cluster and never committed. To add the SearXNG key to the existing `app-secrets`:

```bash
kubectl patch secret app-secrets -n <namespace> \
  -p "{\"stringData\":{\"SEARXNG_SECRET\":\"$(openssl rand -hex 32)\"}}"
kubectl rollout restart deployment/searxng -n <namespace>
```

The deployment marks this key `optional`, so SearXNG still starts on the placeholder in `searxng/settings.yml` until the key is added.

## What is public, on purpose

Azure resource names appear in the k8s manifests and workflows: the ACR registry, resource group, AKS cluster, and the Postgres, Redis and Event Hubs hostnames. These are addresses, not credentials. Reaching any of them still takes a key from the table above or an Azure login. They are also already in git history, so removing them from the current files would not hide them.

## If a secret leaks

Rotate it at the source (DeepSeek dashboard, Azure Portal → Access keys, `openssl rand` for SearXNG), update the store it lives in, and restart the pods that read it. Deleting the commit does not help: forks and clones keep it.

The SearXNG key that used to sit in `searxng/settings.yml` is in git history and must be treated as leaked. Generate a new one with the `kubectl patch` above.

<!-- branch protection test: direct push to main -->
