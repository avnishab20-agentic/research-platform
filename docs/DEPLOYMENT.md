# Deployment and CI/CD

How code gets from a pull request to the running app on Azure, what checks it
has to pass on the way, and how the Azure resources are set up.

---

## What runs where

Everything lives in one Azure resource group, `research-platform-rg`:

| Azure resource | Name | Used for |
|---|---|---|
| Kubernetes cluster (AKS) | `research-platform-aks` | runs all the app containers (pods) |
| Container Registry (ACR) | `researchplatformacr` | stores the built Docker images |
| Database for PostgreSQL | `research-platform-pg` | the database, with the pgvector extension turned on |
| Cache for Redis | `research-platform-redis2` | retrieval-service's cache and rate limiters |
| Event Hubs | `research-platform-eventhubs` | Kafka-compatible message queues |

Inside the cluster, everything runs in the namespace
`namespace-workflow-1790106905740` (a namespace is a named folder inside the
cluster):

| Deployment | Manifests | Notes |
|---|---|---|
| `retrieval-service-cicd` | `retrieval-service/k8s/manifests/` | 1–2 copies, scaled by CPU (autoscaler) |
| `agent-service` | `agent-service/k8s/` | runs all three agent roles |
| `control-plane` | `control-plane/k8s/` | |
| `extractor` | `extractor/k8s/` | |
| `searxng` | `searxng/k8s/` | public image; its `settings.yml` is loaded from the repo |
| `front-door` (Caddy) | `k8s/front-door.yaml` | the only public entry point, see below |

**The front door.** Caddy is a small web server that gets a free HTTPS
certificate from Let's Encrypt and renews it automatically. It is the only
thing reachable from the internet. It sends `/api/v1/runs…` to control-plane
and everything else (the web page, search and extract) to retrieval-service.
Because the browser sees one address, no CORS setup is needed.

**Shared settings** (database URL, Kafka address) live in the ConfigMap in
`k8s/platform-config.yaml`. Passwords and keys live in Kubernetes Secrets that
are created by hand and never committed. See [SECRETS.md](SECRETS.md).

---

## How a change reaches the cluster

### 1. Pull request: checks only, nothing is deployed

| Workflow | Runs on | Does |
|---|---|---|
| `backend-pr-validation` | every pull request | `mvn verify` over all modules (compile + all tests + coverage), SonarQube Cloud scan, and builds each Java service's Docker image without pushing it |
| per-service workflows | PRs that change that service | only the **test** stage below |

A ruleset on `main` makes two checks **required**: `maven` and
**SonarCloud Code Analysis** (the Quality Gate). The merge button stays locked
until both pass.

`backend-pr-validation` deliberately runs on *every* PR, with no folder
filter. A required check that never runs (say, on a docs-only PR) would block
that PR forever.

### 2. Merge to `main`: test, build, deploy, verify

Each service has its own workflow (`agent-service.yml`, `control-plane.yml`,
`retrieval-service-cicd.yml`, `extractor.yml`, `searxng.yml`, `platform.yml`).
Each one only runs when its own folder changes, plus `common/`, `pom.xml` for
the Java services, or the workflow files themselves.

All of them call one shared recipe, `.github/workflows/_build-deploy.yml`,
which runs four stages in order. **A failed stage stops everything after it.**

```
test ──▶ build ──▶ deploy ──▶ verify
```

| Stage | What happens | Skipped when |
|---|---|---|
| **test** | the service's own checks: `mvn test` for Java, `smoke_test.py` for the extractor, "is `json` in `search.formats`?" for SearXNG, "does every YAML file parse?" for `k8s/` | no test command given |
| **build** | `docker build` on the GitHub runner, pushed to ACR tagged with the commit SHA (never `latest`, so any version can be traced and rolled back) | public image (searxng) or no image (platform) |
| **deploy** | logs in to Azure, then applies the service's manifests with the new image | never on a PR |
| **verify** | waits up to 5 minutes for the new pods to be ready, then opens a tunnel to one pod and calls its health URL (for example `/actuator/health`). On failure it prints the pod list and the last 100 log lines | no deployment named (platform) |

Two runs of the same workflow on the same branch never overlap, so two deploys
can't collide.

### How GitHub logs in to Azure (no password stored)

The workflows use **OIDC**. For each job, GitHub creates a short-lived signed
token that says "this is repo X, branch Y". Azure checks it against a
**federated credential** on an app registration and lets the job in. No Azure
password or client secret exists anywhere, including in GitHub. The repo only
stores three IDs as GitHub secrets: `AZURE_CLIENT_ID`, `AZURE_TENANT_ID` and
`AZURE_SUBSCRIPTION_ID`.

### Third-party actions are pinned

Actions from outside GitHub (`azure/login`, `azure/use-kubelogin`,
`azure/aks-set-context`, `Azure/k8s-deploy`) are referenced by a full commit
SHA, with the version in a comment:

```yaml
- uses: azure/login@a65d910e8af852a8061c627c456678983e180302 # v2.2.0
```

A tag like `@v2` can be moved by whoever owns that repository. A commit SHA
can't, so a hijacked tag can't slip new code into a job that has access to
Azure. To upgrade, find the new tag's commit (`git ls-remote
https://github.com/azure/login refs/tags/v2.3.0`) and update both the SHA and
the comment.

---

## Code quality: JaCoCo and SonarQube Cloud

- **JaCoCo** (set up in the root `pom.xml`) records which lines the tests run.
  `mvn verify` writes a report per module to `target/site/jacoco/`: open
  `index.html` in a browser, while Sonar reads `jacoco.xml`.
- **SonarQube Cloud** (free for public repos) receives the code and the
  coverage from `backend-pr-validation`. Its settings are in the root
  `pom.xml` (`sonar.organization`, `sonar.projectKey`). Its token is the
  `SONAR_TOKEN` GitHub secret. Without that secret, the scan step skips itself.
- The **Quality Gate** ("Sonar way") fails a PR when changed code has any new
  bug or security issue, or less than 80% test coverage.
- In SonarQube Cloud, **Automatic Analysis must stay off**. It clashes with the
  CI scan, and it can't see coverage anyway.
- Sonar ignores `retrieval-service/.../static/vendor/`. That folder holds
  third-party minified JavaScript (React, htm), copied in so the page doesn't
  depend on a CDN.

---

## Setting up Azure from scratch

Only needed once, or after deleting the resource group. Use one region for
everything.

1. **Resource group** `research-platform-rg`. Everything below goes in it, so
   deleting this one group deletes everything and stops all billing.
2. **Container Registry** `researchplatformacr` (Basic tier).
3. **AKS cluster** `research-platform-aks`. Attach the registry above when
   creating it, so the cluster can pull images without an extra password.
4. **PostgreSQL flexible server** `research-platform-pg`, version 16:
   - under **Server parameters → `azure.extensions`**, allow **`VECTOR`**
     (pgvector). The first Flyway migration runs `CREATE EXTENSION vector`
     and fails without it;
   - create a database named `research`;
   - allow access from Azure services.
5. **Azure Cache for Redis.** Put its host, port and SSL setting in
   `retrieval-service/k8s/manifests/configmap.yaml` and its access key in the
   `secret-ref` Secret.
6. **Event Hubs namespace** `research-platform-eventhubs`, **Standard tier**.
   The Basic tier does not speak the Kafka protocol. Create the 5 topics
   (Event Hubs calls them "event hubs") by hand, because auto-creation isn't
   reliable there:

   | Name | Partitions |
   |---|---|
   | `research.subtasks` | 12 |
   | `research.findings` | 6 |
   | `run.ready` | 3 |
   | `claims.ready` | 3 |
   | `agent.events` | 3 |

7. **Kubernetes Secrets** `app-secrets` and `secret-ref`. The list of keys is in
   [SECRETS.md](SECRETS.md).
8. **GitHub login:** create an app registration, add a federated credential
   for this repo's `main` branch, give it rights to push to the registry and
   deploy to the cluster, then put its three IDs in GitHub secrets.
9. **Public address:** give the front door's public IP a DNS name in Azure
   (currently `avnish-research.southindia.cloudapp.azure.com`) and make sure
   `k8s/front-door.yaml` uses the same name.

Then push to `main`, or run each workflow by hand from the **Actions** tab.

## Keeping costs down

- The biggest saving is **deleting the whole resource group** when you aren't
  using it for a while. Redis and Event Hubs keep charging even when idle;
  deleting is the only way to stop them.
- The app itself caps usage: 20 questions a day (control-plane) and 1,000
  searches a day (retrieval-service).
