# Azure Deployment Strategy — Research Platform on AKS (Portal-only)

A complete, step-by-step plan for taking this project from "runs on my
laptop via `docker compose`" to "runs on Azure Kubernetes Service (AKS)
with managed Postgres, Redis, and Kafka-compatible Event Hubs" —
**entirely through the Azure Portal, no `az` CLI, no `kubectl`, no local
terminal at all.**

**This is a plan to follow, not something already run.** Nothing in this
document has been executed against a real Azure account. Every step below
is a Portal screen you click through yourself — same working style as the
rest of this project, just mouse instead of keyboard commands. Portal
screens shift their exact wording between Azure releases more often than
the CLI does, so where a button/menu label is likely to drift, that's
called out.

**Two things a Portal-only path genuinely cannot avoid, and how we route
around them without a local terminal:**
1. **Building the 3 container images.** There's no Dockerfile in this
   project (Jib builds images from Maven directly) and no local `docker
   build` step to run anyway — so instead of running Maven/Jib on your
   laptop, **Phase 5** uses Azure Container Registry's **"Quick task"**
   feature, which builds an image *inside Azure* from a Dockerfile it
   reads out of your GitHub repo. This means we add one small Dockerfile
   per service (using `mvn` inside a build-stage container), committed to
   the repo — you still never type `docker build` or `mvn` yourself.
2. **Applying Kubernetes YAML.** AKS's Portal has a built-in **"Kubernetes
   resources" / "Workloads"** blade with a YAML editor and an "Apply"
   button — that's what **Phase 7** uses in place of `kubectl apply -f`.
   (Azure Cloud Shell is *technically* Portal-embedded, but it's still a
   terminal — deliberately not used anywhere in this doc.)

**Cost discipline, stated once up front so it's not forgotten:** delete
the whole Resource Group at the end of every session you're not actively
using this. Azure Cache for Redis and Event Hubs don't have a free
"paused" state — only deleting them stops billing. See Phase 8 for the
exact click-path.

---

## Phase 0 — What you need, once

Nothing to install. Just a browser and an Azure account signed in at
**portal.azure.com**. (The $200 first-month credit applies automatically
to a new Pay-As-You-Go subscription — check **Cost Management + Billing**
in the Portal to confirm it's active before Phase 1.)

---

## Phase 1 — The Resource Group

Everything you create lives inside one named folder, so it can all be
found — and deleted — together.

1. Portal search bar (top) → type **"Resource groups"** → **+ Create**.
2. Subscription: your Pay-As-You-Go one.
3. Resource group name: `research-platform-rg`.
4. Region: **Central India** (closest to you; **South India** is the
   other reasonable pick — whichever you choose, use it for *every*
   resource below, since cross-region traffic between your own services
   adds latency and sometimes cost).
5. **Review + create** → **Create**.

---

## Phase 2 — Azure Container Registry (ACR)

A private place to store the 3 Docker images this project builds.

1. Search bar → **"Container registries"** → **+ Create**.
2. Resource group: `research-platform-rg`.
3. Registry name: `researchplatformacr` (must be globally unique across
   *all* of Azure, alphanumeric only, no dashes — if it's taken, the
   Portal shows a red X live as you type; pick another).
4. Location: same region as Phase 1.
5. SKU: **Basic**.
6. **Review + create** → **Create**.

After it's created, open the resource → **Access keys** (left menu) →
toggle **Admin user** to **Enabled**. This lets AKS's own "attach ACR"
wizard (Phase 3) authenticate without you handling a token by hand.

---

## Phase 3 — The AKS cluster

1. Search bar → **"Kubernetes services"** → **+ Create** → **Create a
   Kubernetes cluster**.
2. **Basics** tab:
   - Resource group: `research-platform-rg`.
   - Cluster name: `research-platform-aks`.
   - Region: same as above.
   - Node size: click **Change size** → pick **B2s** (2 vCPU / 4GB) —
     enough for 3 lightweight Spring Boot services plus room for KEDA to
     scale `agent-service` up briefly during a run.
   - Node count: **2**.
3. **Integrations** tab (this is the one that matters most for a smooth
   setup): under **Container registry**, select
   **researchplatformacr** from the dropdown. This is the Portal
   equivalent of `--attach-acr` — it wires up image-pull permissions for
   you, no manual secret needed.
4. Still on **Integrations**: find **KEDA** and toggle it **Enabled**
   (Kafka-lag-based autoscaling for `agent-service`). *(Exact toggle
   wording/location has moved between AKS Portal releases — if it's not
   under Integrations, check the **Advanced** or **Workloads** tab; search
   "AKS KEDA add-on" inside the creation wizard's search box if unsure.)*
5. **Review + create** → **Create**. This takes several minutes.

Once created, open the cluster resource → left menu → **Kubernetes
resources** → **Overview**. This is the browser-based dashboard that
replaces every `kubectl get ...` command for the rest of this doc — no
`az aks get-credentials` needed, since the Portal is already
authenticated as you.

---

## Phase 4 — Managed data services

This is the one part of the plan that's a real architectural swap from
`docker-compose.yml`: Postgres, Redis, and Kafka move from containers you
run yourself to Azure's managed equivalents.

### 4a. Azure Database for PostgreSQL (replaces the `postgres` container)

1. Search bar → **"Azure Database for PostgreSQL flexible servers"** →
   **+ Create**.
2. Resource group: `research-platform-rg`. Server name:
   `research-platform-pg`. Region: same as above.
3. **PostgreSQL version: 16**.
4. Workload type: **Development** (this picks the Burstable tier for
   you) → confirm compute is **Standard_B1ms**, storage **32 GiB**.
5. Admin username: `research`. Password: choose a strong one and note it
   down — it's needed again in Phase 6.
6. **Networking** tab: under **Connectivity method**, pick **Public
   access**, then under **Firewall rules** check **"Allow public access
   from any Azure service within Azure to this server"**. This is the
   Portal checkbox equivalent of the CLI's `--public-access 0.0.0.0`
   special value (it means "reachable by other Azure resources," not
   "open to the whole internet"). The fully-correct production answer is
   VNet-integrated private access, which is more setup than this project
   needs right now.
7. **Review + create** → **Create**.

**This project needs the `pgvector` extension** (for the RAG embeddings
table) — Azure's managed Postgres supports it, but it's off by default:

1. Open the server resource → left menu → **Server parameters**.
2. Search for **`azure.extensions`**.
3. In the value dropdown/checklist, check **`VECTOR`** → **Save**.

The existing Flyway migration's own `CREATE EXTENSION IF NOT EXISTS
vector;` line doesn't need to change — it just now needs this
server-level allowlist step done first, which `docker-compose`'s
`pgvector/pgvector:pg16` image never required (that image ships the
extension pre-installed).

Create the actual database inside the server:

1. Same server resource → left menu → **Databases** → **+ Add**.
2. Name: `research` → **Save**.

### 4b. Azure Cache for Redis (replaces the `redis` container)

1. Search bar → **"Azure Cache for Redis"** → **+ Create**.
2. Resource group: `research-platform-rg`. Name:
   `research-platform-redis`. Location: same region.
3. Cache type: **Basic C0** — the smallest tier, fine for this project's
   cache-aside workload.
4. **Review + create** → **Create**.

Once created, open it → left menu → **Access keys** to get the
**Primary connection string** and **Host name** — needed in Phase 6.

### 4c. Azure Event Hubs (replaces Redpanda — Kafka protocol compatible)

1. Search bar → **"Event Hubs"** → **+ Create** (this creates the
   *namespace* first, one level above individual "Event Hubs").
2. Resource group: `research-platform-rg`. Namespace name:
   `research-platform-eventhubs`. Location: same region.
3. **Pricing tier: Standard — not Basic.** Basic does not support the
   Kafka protocol at all, only Event Hubs' own native protocol, and this
   project's `spring-kafka` consumers need Kafka compatibility.
4. **Review + create** → **Create**.

**Topics need to be pre-created by hand**, unlike this project's current
setup where `control-plane`'s `KafkaTopicConfig` auto-creates them on
every startup. Event Hubs' Kafka-compatibility layer has limited and
version-dependent support for topic auto-creation, so create all 5 "Event
Hubs" (Event Hubs' name for what Kafka calls topics) explicitly, matching
the partition counts already declared in `KafkaTopicConfig.java`:

Open the namespace resource → left menu → **Event Hubs** → **+ Event
Hub**, and repeat 5 times:

| Name | Partition count |
|---|---|
| `research.subtasks` | 12 |
| `research.findings` | 6 |
| `run.ready` | 3 |
| `claims.ready` | 3 |
| `agent.events` | 3 |

Grab the Kafka-compatible connection string (this becomes the
`sasl.jaas.config` password in Spring config, below):

1. Namespace resource → left menu → **Shared access policies** →
   **RootManageSharedAccessKey**.
2. Copy **Connection string–primary key** — needed in Phase 6.

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

## Phase 5 — Build and push the 3 container images (via ACR Quick Task, no local build)

ACR can build an image **inside Azure** straight from a Dockerfile it
pulls out of your GitHub repo — no local Docker, no local Maven/Jib run.
That does mean each service needs a Dockerfile committed to the repo
first (the project has none today, since Jib normally builds images
without one):

```dockerfile
# <service>/Dockerfile — same shape for retrieval-service, agent-service, control-plane
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY common ./common
COPY <service> ./<service>
RUN mvn -pl <service> -am -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /app/<service>/target/*.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

(Ask Claude to write the three real Dockerfiles from this shape and
commit them — that part is still code, just not a deploy *step* you run
by hand.)

Then, per service, entirely in the Portal:

1. Open the `researchplatformacr` resource → left menu → **Quick task**
   *(older Portal versions call this "Tasks" → "Quick run")*.
2. **Source**: point it at this GitHub repo, the branch, and the
   Dockerfile path (e.g. `retrieval-service/Dockerfile`).
3. **Image name**: `retrieval-service:latest` (and `agent-service`,
   `control-plane` for the other two runs).
4. **Run**. ACR builds the image in an Azure-hosted build container and
   pushes it straight into the same registry — nothing touches your
   laptop.
5. Repeat for the other two services.

Confirm all 3 landed: registry resource → **Repositories** (left menu)
should list `retrieval-service`, `agent-service`, `control-plane`.

**If you'd rather automate this step** (build-on-push instead of
clicking Quick task 3 times per change), ACR also has **Tasks → + Add →
Task**, a Portal wizard that watches your GitHub repo and rebuilds
automatically on every push — worth switching to once the manual path
above works once.

---

## Phase 6 — Secrets

Everything sensitive (DB password, Redis key, Event Hubs connection
string, `DEEPSEEK_API_KEY`) goes into one Kubernetes Secret, referenced
by each Deployment as environment variables — never baked into an image
or committed to the repo.

AKS's Portal exposes the same "Kubernetes resources" YAML editor used for
Deployments in Phase 7 — a Secret is just another YAML resource applied
the same way:

1. AKS cluster resource → left menu → **Kubernetes resources** →
   **Secrets** → **+ Add**.
2. If the Portal offers a form (name/key/value rows), use it directly.
   If it only offers a YAML box, paste this (values base64-encode
   automatically in the form path; in raw YAML you must base64 them
   yourself — the Portal's **Secrets** form path avoids that manual step,
   prefer it):

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: app-secrets
type: Opaque
stringData:
  DB_PASSWORD: "<the postgres password from Phase 4a>"
  REDIS_KEY: "<the redis primary key from Phase 4b>"
  EVENTHUBS_CONNECTION_STRING: "<the connection string from Phase 4c>"
  DEEPSEEK_API_KEY: "<your DeepSeek key>"
```

(`stringData` — not `data` — accepts plain text directly and Kubernetes
base64-encodes it for you; this is the one YAML shape here where skipping
manual base64 is a real Portal-vs-CLI improvement, not just a
reformatting.)

---

## Phase 7 — Kubernetes manifests, applied via the Portal's YAML editor

One `Deployment` + `Service` pair per service. `agent-service`'s is the
one that also gets a `ScaledObject` (Phase 7d) instead of a fixed replica
count.

For each YAML block below: AKS cluster resource → **Kubernetes
resources** → **Workloads** (for Deployments) or **Services and
ingresses** (for Services) → **+ Create** → **Apply a YAML** (exact
label varies by Portal version — look for a raw-YAML option alongside
the form-based one) → paste → **Add**.

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
referenced a second way for KEDA specifically. Apply it through the same
**Apply a YAML** path.)

After applying each YAML block, watch it come up under **Kubernetes
resources → Workloads** — the Portal shows live pod status (Running /
CrashLoopBackOff / Pending) in place of `kubectl get pods`, and clicking
a pod shows its logs in place of `kubectl logs`.

---

## Phase 8 — Ingress, public URL, and cost teardown

**Ingress** — AKS's built-in **"App routing"** add-on is the least manual
path (managed nginx ingress, no Helm install needed):

1. AKS cluster resource → left menu → **Networking**.
2. Find **App routing** (or **Application routing add-on**, wording
   varies by Portal version) → **Enable**.

*(Verify the exact menu location when you arrive here — this add-on's
Portal placement has moved across AKS releases; search "AKS app routing"
within the cluster's left-menu search box if it's not under
Networking.)* Once enabled, add an `Ingress` resource (via the same
**Apply a YAML** path from Phase 7) routing to `control-plane`'s Service
— this gets you a public IP. A real domain or a free `nip.io`-style
hostname can point at it the same way the original `docs/PLAN.md`
describes for the plain-VM path.

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

1. AKS cluster resource → left menu → **Insights** (or **Monitoring →
   Insights**).
2. If not already enabled, click **Enable** / **Configure monitoring** →
   accept the default Log Analytics workspace → **Configure**.

Combined with each Deployment's `SPRING_PROFILES_ACTIVE=json` (Phase 7),
this is the "whatever logging application I decide on later" moment
actually arriving — Log Analytics becomes queryable by `runId` almost
immediately, via **Logs** (left menu) with a KQL query box in-browser.

**Tear it all down** when you're done for the session — the single most
important step in this whole document for staying inside the $200
credit:

1. Search bar → **"Resource groups"** → open `research-platform-rg`.
2. Top toolbar → **Delete resource group**.
3. Type the resource group's name to confirm → **Delete**.

Everything created in every phase above lives in this one Resource
Group, so this one action removes all of it and stops all billing.
Recreate from Phase 1 next time.

---

## Phase 9 — CI/CD (optional, once the manual path above works once)

Once you've deployed by hand successfully at least once, the AKS
resource's own **Deployment Center** blade sets up a GitHub Actions
pipeline through a Portal wizard — no YAML hand-authored, no local `gh`
or `az` CLI:

1. AKS cluster resource → left menu → **Deployment Center** → **Get
   started**.
2. Connect your GitHub account and pick this repo/branch.
3. The wizard generates a GitHub Actions workflow file for you (uses
   Workload Identity Federation / OIDC under the hood, so no long-lived
   Azure secret is stored in GitHub) and offers to commit it directly to
   the repo through the same GitHub connection.
4. Review the generated workflow before accepting — confirm it runs
   `mvn test` (fail fast on the existing test suite) before the build/push
   steps, and that it applies the `k8s/` manifests on success.

Not built yet — this is the natural next step once the manual deploy in
Phases 1-8 is proven to work end to end.

---

## Recap: cost-safe habits

- Delete the Resource Group (Phase 8's last step) at the end of every
  session — this is the only way to stop Redis/Event Hubs billing.
- AKS's control plane itself is free; only the node VMs and the managed
  data services actually cost money.
- Recreating the whole environment from this document takes maybe 45-60
  minutes of Portal clicking (a bit longer than the CLI path, since
  wizards involve more screens than commands) — still cheaper in time
  than leaving it running idle for a few unused days.
