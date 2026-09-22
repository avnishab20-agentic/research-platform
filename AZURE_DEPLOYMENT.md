# Azure Deployment Strategy — Research Platform on AKS

A complete, step-by-step plan for taking this project from "runs on my
laptop via `docker compose`" to "runs on Azure Kubernetes Service (AKS)
with managed Postgres, Redis, and Kafka-compatible Event Hubs."

**This is a plan to follow, not something already run.** Nothing in this
document has been executed against a real Azure account — every `az`
command below is written to be copy-pasted and run by you, so you're the
one clicking/typing, same working style as the rest of this project.
CLI flags on fast-moving services (KEDA add-on, AKS "app routing" add-on)
are noted where they're worth double-checking against current docs before
you run them, since Azure's CLI surface shifts between releases.

**Cost discipline, stated once up front so it's not forgotten:** delete
the whole Resource Group (`az group delete`) at the end of every session
you're not actively using this. Azure Cache for Redis and Event Hubs
don't have a free "paused" state — only deleting them stops billing. See
Phase 8 for the exact command.

---

## Phase 0 — What you need installed locally, once

| Tool | Why | Check |
|---|---|---|
| Azure CLI (`az`) | Everything below is an `az` command | `az --version` |
| `kubectl` | Talks to the AKS cluster once it exists | `kubectl version --client` |
| Helm (optional) | Only needed if you install ingress-nginx by hand instead of AKS's built-in add-on | `helm version` |

Log in once: `az login` (opens a browser, confirms your account).

---

## Phase 1 — The Resource Group

Everything you create lives inside one named folder, so it can all be
found — and deleted — together.

```bash
az group create --name research-platform-rg --location centralindia
```

`centralindia` is the closest Azure region to you; `southindia` is the
other reasonable choice. Pick one and stay consistent — every resource
below should use the same `--location`.

---

## Phase 2 — Azure Container Registry (ACR)

A private place to store the 3 Docker images this project builds.

```bash
az acr create \
  --resource-group research-platform-rg \
  --name researchplatformacr \
  --sku Basic
```

ACR names must be globally unique across all of Azure and alphanumeric
only (no dashes) — if `researchplatformacr` is taken, pick another.

---

## Phase 3 — The AKS cluster

```bash
az aks create \
  --resource-group research-platform-rg \
  --name research-platform-aks \
  --node-count 2 \
  --node-vm-size Standard_B2s \
  --attach-acr researchplatformacr \
  --generate-ssh-keys
```

- `--node-count 2` / `Standard_B2s`: two small (2 vCPU / 4GB) nodes —
  enough for 3 lightweight Spring Boot services plus room for KEDA to
  scale `agent-service` up briefly during a run.
- `--attach-acr`: the one flag that matters most for a smooth setup — it
  lets AKS pull images from your ACR without you having to create and
  manage a separate image-pull secret by hand.

Point `kubectl` at the new cluster:

```bash
az aks get-credentials --resource-group research-platform-rg --name research-platform-aks
kubectl get nodes   # should show your 2 nodes as Ready
```

**Enable KEDA** (Kafka-lag-based autoscaling for `agent-service`) as a
managed AKS add-on:

```bash
az aks update \
  --resource-group research-platform-rg \
  --name research-platform-aks \
  --enable-keda
```

*(Verify this exact flag against `az aks update --help` when you get
here — AKS add-on flags occasionally get renamed between CLI versions.
If `--enable-keda` isn't recognized, search "AKS KEDA add-on enable" for
the current command.)*

---

## Phase 4 — Managed data services

This is the one part of the plan that's a real architectural swap from
`docker-compose.yml`: Postgres, Redis, and Kafka move from containers you
run yourself to Azure's managed equivalents.

### 4a. Azure Database for PostgreSQL (replaces the `postgres` container)

```bash
az postgres flexible-server create \
  --resource-group research-platform-rg \
  --name research-platform-pg \
  --location centralindia \
  --admin-user research \
  --admin-password '<CHOOSE-A-STRONG-PASSWORD>' \
  --sku-name Standard_B1ms \
  --tier Burstable \
  --storage-size 32 \
  --version 16 \
  --public-access 0.0.0.0
```

`--public-access 0.0.0.0` is Azure's special value meaning "allow other
Azure services to reach this" (not "open to the whole internet" — that
would be a real range like `0.0.0.0-255.255.255.255`, don't use that).
For a personal project this is a reasonable tradeoff; the fully-correct
production answer is VNet-integrated private access, which is more setup
than this project needs right now.

**This project needs the `pgvector` extension** (for the RAG embeddings
table) — Azure's managed Postgres supports it, but it has to be turned on
explicitly, in two steps:

```bash
az postgres flexible-server parameter set \
  --resource-group research-platform-rg \
  --server-name research-platform-pg \
  --name azure.extensions --value vector
```

Then, once connected to the database itself (e.g. via `psql`), the
existing Flyway migration's own `CREATE EXTENSION IF NOT EXISTS vector;`
will work — that line doesn't need to change, it just now needs the
server-level allowlist step above done first, which `docker-compose`'s
`pgvector/pgvector:pg16` image never required (that image ships the
extension pre-installed).

Create the actual database inside the server:

```bash
az postgres flexible-server db create \
  --resource-group research-platform-rg \
  --server-name research-platform-pg \
  --database-name research
```

### 4b. Azure Cache for Redis (replaces the `redis` container)

```bash
az redis create \
  --resource-group research-platform-rg \
  --name research-platform-redis \
  --location centralindia \
  --sku Basic \
  --vm-size c0
```

Basic/C0 is the smallest tier — fine for this project's cache-aside
workload. Grab the connection details afterward:

```bash
az redis show --resource-group research-platform-rg --name research-platform-redis --query hostName
az redis list-keys --resource-group research-platform-rg --name research-platform-redis
```

### 4c. Azure Event Hubs (replaces Redpanda — Kafka protocol compatible)

**Must be Standard tier, not Basic** — Basic does not support the Kafka
protocol at all, only Event Hubs' own native protocol.

```bash
az eventhubs namespace create \
  --resource-group research-platform-rg \
  --name research-platform-eventhubs \
  --location centralindia \
  --sku Standard
```

**Topics need to be pre-created by hand**, unlike this project's current
setup where `control-plane`'s `KafkaTopicConfig` auto-creates them on
every startup. Event Hubs' Kafka-compatibility layer has limited and
version-dependent support for topic auto-creation, so the safe path is
creating all 5 "Event Hubs" (Event Hubs' name for what Kafka calls
topics) explicitly, matching the partition counts already declared in
`KafkaTopicConfig.java`:

```bash
az eventhubs eventhub create --resource-group research-platform-rg \
  --namespace-name research-platform-eventhubs --name research.subtasks --partition-count 12

az eventhubs eventhub create --resource-group research-platform-rg \
  --namespace-name research-platform-eventhubs --name research.findings --partition-count 6

az eventhubs eventhub create --resource-group research-platform-rg \
  --namespace-name research-platform-eventhubs --name run.ready --partition-count 3

az eventhubs eventhub create --resource-group research-platform-rg \
  --namespace-name research-platform-eventhubs --name claims.ready --partition-count 3

az eventhubs eventhub create --resource-group research-platform-rg \
  --namespace-name research-platform-eventhubs --name agent.events --partition-count 3
```

Grab the Kafka-compatible connection string (this becomes the
`sasl.jaas.config` password in Spring config, below):

```bash
az eventhubs namespace authorization-rule keys list \
  --resource-group research-platform-rg \
  --namespace-name research-platform-eventhubs \
  --name RootManageSharedAccessKey \
  --query primaryConnectionString
```

**The Spring Kafka config changes for real here** — Redpanda today runs
fully plaintext; Event Hubs requires SASL over TLS. In both
`agent-service` and `control-plane`'s `application.yml`, the
`spring.kafka` block needs these additions once deployed to Azure
(exact values as environment-variable placeholders, filled from
Kubernetes Secrets — see Phase 6):

```yaml
spring:
  kafka:
    bootstrap-servers: research-platform-eventhubs.servicebus.windows.net:9093
    properties:
      security.protocol: SASL_SSL
      sasl.mechanism: PLAIN
      sasl.jaas.config: >
        org.apache.kafka.common.security.plain.PlainLoginModule required
        username="$ConnectionString"
        password="${EVENTHUBS_CONNECTION_STRING}";
```

This is genuinely new configuration, not a copy-paste of what's already
there — budget real time to get this connecting correctly the first time.

---

## Phase 5 — Build and push the 3 container images

**Prerequisite: add the Jib Maven plugin** to each of the three service
poms (`retrieval-service`, `agent-service`, `control-plane` — not
`common`, which has no main class). This hasn't been added to the
project yet; add it when you're ready to actually deploy, not before:

```xml
<plugin>
    <groupId>com.google.cloud.tools</groupId>
    <artifactId>jib-maven-plugin</artifactId>
    <version>3.4.3</version>
</plugin>
```

Log in so Jib can push to your ACR:

```bash
az acr login --name researchplatformacr
```

Build and push each service (run from the repo root):

```bash
mvn -pl retrieval-service -am compile jib:build \
  -Dimage=researchplatformacr.azurecr.io/retrieval-service:latest

mvn -pl agent-service -am compile jib:build \
  -Dimage=researchplatformacr.azurecr.io/agent-service:latest

mvn -pl control-plane -am compile jib:build \
  -Dimage=researchplatformacr.azurecr.io/control-plane:latest
```

Jib reads each module's actual resolved dependencies (including
`common`'s compiled classes) and builds a correct, self-contained image
per service — no Dockerfile needed, and Jib doesn't require a running
Docker daemon to do this.

---

## Phase 6 — Secrets

Everything sensitive (DB password, Redis key, Event Hubs connection
string, `DEEPSEEK_API_KEY`) goes into one Kubernetes Secret, referenced
by each Deployment as environment variables — never baked into an image
or committed to the repo.

```bash
kubectl create secret generic app-secrets \
  --from-literal=DB_PASSWORD='<the postgres password from Phase 4a>' \
  --from-literal=REDIS_KEY='<the redis key from Phase 4b>' \
  --from-literal=EVENTHUBS_CONNECTION_STRING='<the connection string from Phase 4c>' \
  --from-literal=DEEPSEEK_API_KEY='<your DeepSeek key>'
```

---

## Phase 7 — Kubernetes manifests

One `Deployment` + `Service` pair per service. `agent-service`'s is the
one that gets a `ScaledObject` (Phase 7d) instead of a fixed replica
count.

### 7a. `retrieval-service`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: retrieval-service
spec:
  replicas: 2
  selector:
    matchLabels: {app: retrieval-service}
  template:
    metadata:
      labels: {app: retrieval-service}
    spec:
      containers:
        - name: retrieval-service
          image: researchplatformacr.azurecr.io/retrieval-service:latest
          ports: [{containerPort: 8081}]
          env:
            - {name: SPRING_PROFILES_ACTIVE, value: "json"}
            - name: DEEPSEEK_API_KEY
              valueFrom: {secretKeyRef: {name: app-secrets, key: DEEPSEEK_API_KEY}}
            # ... REDIS_KEY, etc. — same secretKeyRef pattern
---
apiVersion: v1
kind: Service
metadata:
  name: retrieval-service
spec:
  selector: {app: retrieval-service}
  ports: [{port: 8081, targetPort: 8081}]
```

### 7b. `agent-service` and 7c. `control-plane`

Same shape as 7a — different image, different port (`8082`/`8083`), and
`control-plane` is the only one that needs an `Ingress` (Phase 8) since
it's the only one meant to be reachable from outside the cluster.

### 7d. KEDA `ScaledObject` for `agent-service`

Rather than the generic `kafka` scaler (which would need to authenticate
against Event Hubs' Kafka-compat SASL endpoint — extra auth complexity),
KEDA ships a purpose-built **`azure-eventhub`** scaler that talks to
Event Hubs directly and reads real consumer-group lag:

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: agent-service-researcher-scaler
spec:
  scaleTargetRef:
    name: agent-service
  minReplicaCount: 1
  maxReplicaCount: 6
  triggers:
    - type: azure-eventhub
      metadata:
        eventHubName: research.subtasks
        consumerGroup: agent-service-researcher
      authenticationRef:
        name: eventhub-auth
```

(A `TriggerAuthentication` resource pointing at `app-secrets`'
`EVENTHUBS_CONNECTION_STRING` is needed alongside this — same secret,
referenced a second way for KEDA specifically.)

Apply everything:

```bash
kubectl apply -f k8s/
```

*(Organize the manifests above into a `k8s/` directory in the repo when
you get here — not created yet, this doc describes the shape.)*

---

## Phase 8 — Ingress, public URL, and cost teardown

**Ingress** — AKS's built-in "app routing" add-on is the least manual
path (managed nginx ingress, no Helm install needed):

```bash
az aks approuting enable --resource-group research-platform-rg --name research-platform-aks
```

*(Again, verify this exact subcommand against current `az aks --help`
when you arrive here — this add-on's command surface has changed across
AKS versions.)* Once enabled, an `Ingress` resource routing to
`control-plane`'s Service gets you a public IP; a real domain or a free
`nip.io`-style hostname can point at it the same way the original
`docs/PLAN.md` describes for the plain-VM path.

**JWT auth belongs here, not skipped.** `control-plane` is the one
service this Ingress exposes to the public internet — the JWT
login/token/DB-verification auth you're building yourself (see the
earlier discussion in this project) needs to be in place on
`control-plane` *before* this Ingress goes live for real, the same way
`docs/PLAN.md`'s original Week 5 plan sequenced it.

**Logging payoff** — this project already has JSON-structured logging
ready (`docs/progress/2026-09-22q-structured-logging.md`). On AKS, turn
on Container Insights to ship every pod's stdout straight into Azure Log
Analytics with zero extra app code:

```bash
az aks enable-addons \
  --resource-group research-platform-rg \
  --name research-platform-aks \
  --addons monitoring
```

Combined with each Deployment's `SPRING_PROFILES_ACTIVE=json` (Phase 7),
this is the "whatever logging application I decide on later" moment
actually arriving — Log Analytics becomes queryable by `runId` almost
immediately.

**Tear it all down** when you're done for the session — the single most
important command in this whole document for staying inside the $200
credit:

```bash
az group delete --name research-platform-rg --yes --no-wait
```

Everything created in every phase above lives in this one Resource
Group, so this one command removes all of it and stops all billing.
Recreate from Phase 1 next time.

---

## Phase 9 — CI/CD (optional, once the manual path above works once)

Once you've deployed by hand successfully at least once, a GitHub
Actions workflow can automate Phase 5 + Phase 7's `kubectl apply`:

1. **Auth**: use Workload Identity Federation (OIDC) so GitHub Actions
   authenticates to Azure without a long-lived stored secret — the
   modern, more secure pattern over a Service Principal password.
2. **Build**: `mvn test` (fail fast on the existing 127-test suite),
   then the three `jib:build` commands from Phase 5.
3. **Deploy**: `az aks get-credentials` inside the workflow, then
   `kubectl apply -f k8s/`.

Not built yet — this is the natural next step once the manual deploy in
Phases 1-8 is proven to work end to end.

---

## Recap: cost-safe habits

- Delete the Resource Group (`az group delete`) at the end of every
  session — this is the only way to stop Redis/Event Hubs billing.
- AKS's control plane itself is free; only the node VMs and the managed
  data services actually cost money.
- Recreating the whole environment from this document takes maybe 30-45
  minutes of `az` commands — cheaper in time than leaving it running
  idle for a few unused days.
