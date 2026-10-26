## Install MockServer Helm Chart

### Prerequisites

- A Kubernetes cluster
- [`kubectl`](https://kubernetes.io/docs/tasks/tools/) configured to talk to it
- [Helm](https://helm.sh/docs/intro/quickstart/) 3.8 or later (OCI support is enabled by default from 3.8)

### Helm Install

The chart is published to the **GitHub Container Registry (GHCR) as an OCI artifact** — no `helm repo add` is needed, `helm install` can pull straight from `oci://`:

```bash
helm upgrade --install --create-namespace --namespace mockserver --version 9.2.0 mockserver oci://ghcr.io/mock-server/charts/mockserver
```

The OCI artifact is public — no authentication is required for `helm pull` / `helm install`. Browse the published versions on the [GHCR package page](https://github.com/orgs/mock-server/packages/container/package/charts%2Fmockserver) and pass the one you want with `--version` (omit `--version` to get the latest).

**OR** install from the legacy `.tgz` served by `www.mock-server.com`:

```bash
helm upgrade --install --create-namespace --namespace mockserver mockserver https://www.mock-server.com/mockserver-9.2.0.tgz
```

**OR** if you have the helm chart source folder (i.e. you have the repository cloned):

```bash
helm upgrade --install --create-namespace --namespace mockserver mockserver helm/mockserver
```

### Verifying the chart signature

The OCI chart is signed with [cosign](https://github.com/sigstore/cosign) using MockServer's signing key, published at **https://www.mock-server.com/mockserver-cosign.pub** (the same key signs the Docker images):

```bash
cosign verify \
  --key https://www.mock-server.com/mockserver-cosign.pub \
  ghcr.io/mock-server/charts/mockserver:9.2.0
```

Each of the commands above installs MockServer into a **namespace** called `mockserver` with default configuration (as per the embedded [values.yaml](https://github.com/mock-server/mockserver-monorepo/blob/master/helm/mockserver/values.yaml)).  
MockServer will then be available on domain name `mockserver.mockserver.svc.cluster.local`, as long as the namespace you are calling from isn't prevented (by network policy) to call the `mockserver` namespace.

**THEN**

To view the logs:

```bash
kubectl -n mockserver logs --tail=100 -l app=mockserver,release=mockserver
```

To wait until the deployment is complete run:

```bash
kubectl -n mockserver rollout status deployments mockserver
```

To check the status of the deployment without waiting, run the following command and confirm the `mockserver` has the `Running` status:

```bash 
kubectl -n mockserver get po -l release=mockserver
```

### Basic MockServer Configuration 

Modify the arguments MockServer starts with by setting values explicitly using `--set`, as follows:

```bash
helm upgrade --install --create-namespace --namespace mockserver --set app.serverPort=1080 --set app.logLevel=INFO mockserver oci://ghcr.io/mock-server/charts/mockserver
```

The following values are supported:
- `app.serverPort` (default: 1080)
- `app.logLevel` (default: INFO)
- `app.proxyRemoteHost` (no default)
- `app.proxyRemotePort` (no default)
- `app.jvmOptions` (default: `""`) - extra JVM options passed via `JAVA_TOOL_OPTIONS`. The image already caps the heap at 45% of the container memory limit (`-XX:MaxRAMPercentage=45.0`); set an explicit `-Xmx` here to change it (an explicit `-Xmx` disables `MaxRAMPercentage`). The cap is 45% because the process needs the rest outside the heap (metaspace, thread stacks, GC bookkeeping, Netty's off-heap buffers and kernel socket buffers); `512Mi` is the smallest supported `resources.limits.memory` (768Mi for a clustered node with the Infinispan backend). Always set `resources.limits.memory` so the heap is sized off a bounded container limit rather than total node memory. The image runs ZGC by default (via its own `JAVA_TOOL_OPTIONS`); the chart only sets `JAVA_TOOL_OPTIONS` when `app.jvmOptions` is non-empty, and that **replaces** the image default — so a non-empty `app.jvmOptions` that omits a collector drops ZGC. Include the collector when you set it (e.g. `"-XX:+UseZGC -Xmx512m"`), or use `"-XX:+UseG1GC"` to switch to G1.
- `image.repository` (default: `mockserver`) - registry and namespace the `mockserver` image is pulled from; set `ghcr.io/mock-server` to pull the identical images from the GitHub Container Registry instead of Docker Hub, for example when Docker Hub's pull limit is a problem (snapshot images are only on Docker Hub). For the admission webhook also set `webhook.image.repository=ghcr.io/mock-server/mockserver-webhook`
- `image.snapshot` (default: false) - set `true` to use latest snapshot version
- `image.variant` (default: `""`) - image variant appended to the tag: `graaljs` for JavaScript templates, or `http3`, a deprecated alias of the standard image kept for one release. The standard image serves experimental HTTP/3: set `mockserver.http3Port` in the properties file and expose that port as UDP on your Service, which the chart does not add

For example configure a proxyRemoteHost and proxyRemotePort, as follows:

```bash
helm upgrade --install --create-namespace --namespace mockserver --set app.serverPort=1080 --set app.proxyRemoteHost=www.mock-server.com --set app.proxyRemotePort=443 mockserver oci://ghcr.io/mock-server/charts/mockserver
```

Double check the correct arguments have been passed to the pod, as follows:

```bash
kubectl -n mockserver logs -l app=mockserver,release=mockserver
``` 

### Detailed MockServer Configuration

There are two ways to provide MockServer configuration (properties file, expectation initialization JSON, TLS certificates, etc.):

1. **Inline configuration** (recommended) — provide configuration directly in `values.yaml`
2. **External ConfigMap** — create a ConfigMap separately (manually, via CI, or using the example [mockserver-config](https://github.com/mock-server/mockserver-monorepo/tree/master/helm/mockserver-config) chart)

Both approaches mount configuration into the container at `/config`. See [MockServer Configuration](https://www.mock-server.com/mock_server/configuration_properties.html) for details of all configuration options.

#### Option 1: Inline Configuration (single chart install)

Set `app.config.enabled=true` and provide configuration content directly in values. This creates a ConfigMap as part of the main chart — no separate chart or manual ConfigMap creation is needed.

Using `--set-string` (commas in JSON must be escaped as `\,`):

```bash
helm upgrade --install --create-namespace --namespace mockserver \
  --set app.config.enabled=true \
  --set app.config.properties="mockserver.initializationJsonPath=/config/initializerJson.json" \
  --set-string 'app.config.initializerJson=[{"httpRequest":{"path":"/example"}\,"httpResponse":{"body":"response"}}]' \
  mockserver helm/mockserver
```

> **Note:** Helm's `--set` flag treats commas as key/value separators, which corrupts JSON values. Use `--set-string` with escaped commas (`\,`) for simple cases, or a `values.yaml` file (recommended) for anything non-trivial.

Or using a `values.yaml` file (recommended for complex configuration):

```yaml
app:
  config:
    enabled: true
    properties: |
      mockserver.initializationJsonPath=/config/initializerJson.json
      mockserver.enableCORSForAPI=true
      mockserver.enableCORSForAllResponses=true
    initializerJson: |
      [
        {
          "httpRequest": { "path": "/example" },
          "httpResponse": { "body": "some response" }
        }
      ]
```

```bash
helm upgrade --install --create-namespace --namespace mockserver -f values.yaml mockserver helm/mockserver
```

The following inline config values are supported:
- `app.config.enabled` (default: false) - set `true` to create a ConfigMap from inline values
- `app.config.properties` (default: "") - content of `mockserver.properties`
- `app.config.initializerJson` (default: "") - content of `initializerJson.json`
- `app.config.extraFiles` (default: {}) - map of additional filenames to content (e.g. TLS certificates)

#### Option 2: External ConfigMap

If a ConfigMap called `mockserver-config` (or a custom name) exists in the same namespace, it will be mounted into the MockServer container at `/config`.
This ConfigMap can contain a `mockserver.properties` file and other related configuration files such as:
- [json expectation initialization](https://www.mock-server.com/mock_server/initializing_expectations.html), or
- custom [TLS CA, X.509 Certificate or Private Key](https://www.mock-server.com/mock_server/HTTPS_TLS.html#configuration)

The `mockserver.properties` file should load these additional files from the directory `/config` which is the `mountPath` for the ConfigMap.

The mapping of the configuration ConfigMap can be configured as follows:
- `app.mountedConfigMapName` (default: mockserver-config) - name of the configuration ConfigMap (in the same namespace) to mount
- `app.propertiesFileName` (default: mockserver.properties) - path of the property file in the ConfigMap

For example:

```bash
helm upgrade --install --create-namespace --namespace mockserver --set app.mountedConfigMapName=other-mockserver-config --set app.propertiesFileName=other-mockserver.properties mockserver helm/mockserver
```

An example of a helm chart to create this ConfigMap is [helm/mockserver-config](https://github.com/mock-server/mockserver-monorepo/tree/master/helm/mockserver-config)

### Extending MockServer Classpath

To use [class callbacks](https://www.mock-server.com/mock_server/creating_expectations.html#button_response_class_callback) or an [expectation initializer class](https://www.mock-server.com/mock_server/initializing_expectations.html#expectation_initializer_class) the classpath for MockServer must include the specified classes.
To support adding classes to the classpath if a configmap called `mockserver-config` exists in the same namespace any jar files contained in this configmap will be added into MockServer classpath.

The mapping of the libs configmap can be configured as follows: 
- `app.mountedLibsConfigMapName` (default: mockserver-config) - name of the libs configmap (in the same namespace) to mount

For example:

```bash
helm upgrade --install --create-namespace --namespace mockserver --set app.mountedLibsConfigMapName=mockserver-libs mockserver helm/mockserver
```

### Extensions

[Extensions](https://www.mock-server.com/mock_server/extensions.html) are your own Java classes that MockServer loads when it starts. Every MockServer image loads JARs from `/extensions`, so put the JARs in any Kubernetes volume and name the classes:

```yaml
extensions:
  classes: "com.acme.AcmeExtension"   # sets MOCKSERVER_EXTENSIONS
  scanning: false                     # true also loads META-INF/services entries
  volume:                             # any volume source, mounted read-only at /extensions
    configMap:
      name: mockserver-extensions
```

The volume is always mounted read-only. MockServer refuses to start if persisted expectations, persisted recorded expectations or requests, the audit log or the memory-usage CSV is enabled and would be written into `/extensions`.

### Persistent Storage

By default, expectations are held in memory and lost when a pod restarts. To persist expectations across pod restarts, enable persistent storage:

```bash
helm upgrade --install --namespace mockserver \
  --set app.persistence.enabled=true \
  mockserver helm/mockserver
```

This creates a PersistentVolumeClaim and automatically configures MockServer to persist and reload expectations from it. No additional configuration is needed.

The following persistence values are supported:
- `app.persistence.enabled` (default: false) — enable persistent storage
- `app.persistence.existingClaimName` (default: "") — use an existing PVC instead of creating one
- `app.persistence.storageClass` (default: "") — StorageClass for the PVC (empty = cluster default)
- `app.persistence.accessModes` (default: [ReadWriteOnce]) — PVC access modes
- `app.persistence.size` (default: 256Mi) — PVC size
- `app.persistence.mountPath` (default: /persistence) — mount path inside the container
- `app.persistence.annotations` (default: {}) — annotations for the PVC

When persistence is enabled, the chart automatically sets `MOCKSERVER_PERSIST_EXPECTATIONS`, `MOCKSERVER_PERSISTED_EXPECTATIONS_PATH`, and `MOCKSERVER_INITIALIZATION_JSON_PATH` environment variables. These are safe defaults — any matching property in your `mockserver.properties` file takes precedence.

**Note:** Chart-managed PVCs are NOT deleted by `helm uninstall`. Delete the PVC manually if you want to remove persisted data.

**Pod securityContext / PVC permissions:** if the pod cannot write to the mounted volume (a common cause of persistence failing on clusters with restrictive defaults), set a pod-level `fsGroup` via `podSecurityContext`, which is rendered verbatim into the Deployment's `spec.template.spec.securityContext`:

```bash
helm upgrade --install --namespace mockserver mockserver oci://ghcr.io/mock-server/charts/mockserver \
  --set app.persistence.enabled=true \
  --set podSecurityContext.fsGroup=2000
```

`podSecurityContext` accepts any pod-level `securityContext` fields (`fsGroup`, `fsGroupChangePolicy`, `runAsGroup`, `seccompProfile`, …) and defaults to `{}` (nothing emitted).

See the [full persistence documentation](https://www.mock-server.com/where/kubernetes.html#helm_persistent_storage) for more examples including existing PVC usage and clustering with shared storage.

### Shared TLS Certificate Authority (required for clustering / multiple replicas)

By default MockServer **dynamically generates its own Certificate Authority (CA)** on each pod. With a single replica that is fine. But with **more than one replica** (for example `replicaCount: 3`, or any clustered deployment) **each pod generates a different CA**. A client that imported one pod's `mockserver-ca.pem` will then get a **TLS trust error the moment the load balancer routes it to a different pod** — and because it depends on which pod you land on, the failure looks **intermittent**. That intermittency is the tell that the pods do not share a CA.

The fix is to give every pod **one shared CA** via a Kubernetes **Secret** (a Secret, not a ConfigMap — it holds a private key) and turn dynamic generation off. The chart does this for you with the `app.tls` values:

```bash
helm upgrade --install --namespace mockserver \
  --set app.tls.enabled=true \
  --set-file app.tls.certificateAuthorityCertificate=ca.pem \
  --set-file app.tls.certificateAuthorityPrivateKey=ca-key.pem \
  mockserver helm/mockserver
```

This creates a Secret containing the CA, mounts it read-only into every pod at `/tls`, and automatically sets `certificateAuthorityCertificate`, `certificateAuthorityPrivateKey`, and `dynamicallyCreateCertificateAuthorityCertificate=false`. Every pod now signs its leaf certificates with the **same** CA, so a single imported `ca.pem` is trusted across the whole fleet.

> **Keep the CA private key out of source control.** Pass the PEM files with `--set-file` at install time, or manage the Secret yourself (for example with SealedSecrets or External Secrets) and reference it:
>
> ```yaml
> app:
>   tls:
>     enabled: true
>     create: false
>     existingSecretName: my-mockserver-tls   # must contain CertificateAuthorityCertificate.pem and CertificateAuthorityPrivateKey.pem
> ```

The following TLS values are supported:
- `app.tls.enabled` (default: false) — mount shared TLS material from a Secret and disable per-pod dynamic CA generation
- `app.tls.create` (default: true) — create the Secret from the inline PEM values; set `false` to use `existingSecretName`
- `app.tls.existingSecretName` (default: "") — name of an existing Secret to mount (required when `create` is false)
- `app.tls.mountPath` (default: /tls) — read-only mount path for the TLS material
- `app.tls.certificateAuthorityCertificate` / `app.tls.certificateAuthorityPrivateKey` (default: "") — CA PEM material (only used when `create` is true; supply via `--set-file`)
- `app.tls.certificate` / `app.tls.privateKey` (default: "") — optional server certificate/key PEM (only used when `create` is true; MockServer derives leaf certificates from the CA when omitted)

If you deliberately keep **dynamic** CA generation on a single replica, enable a writable directory for the generated certificates so MockServer is not forced to write to a non-writable working directory (`.`):

```bash
helm upgrade --install --namespace mockserver \
  --set app.dynamicCertificateDir.enabled=true \
  mockserver helm/mockserver
```

- `app.dynamicCertificateDir.enabled` (default: false) — mount a writable `emptyDir` for dynamically generated certificates
- `app.dynamicCertificateDir.path` (default: /dynamic-certificates) — mount path, also used for `directoryToSaveDynamicSSLCertificate`

### Extra environment variables

Any MockServer property the chart does not expose a dedicated value for can be set through `app.extraEnv`, which is appended verbatim to the container's environment:

```yaml
app:
  extraEnv:
    - name: MOCKSERVER_MAX_EXPECTATIONS
      value: "5000"
    - name: MOCKSERVER_LOG_LEVEL
      valueFrom:
        configMapKeyRef:
          name: my-config
          key: logLevel
```

### Chaos Proxy (fault injection)

MockServer can be deployed as a **chaos proxy** in front of a real upstream Service to inject faults (errors, latency, dropped connections, slow/corrupted responses, rate limits) into the responses your services receive — without changing the calling code. Point a service at this MockServer Service instead of the real upstream (or use it as an egress/forward proxy), and attach an `HttpChaosProfile` (`chaos` block) to a forwarding expectation.

A minimal reverse-proxy example, supplied through the chart's inline configuration:

```yaml
# values-chaos.yaml
app:
  config:
    enabled: true
    properties: |
      mockserver.initializationJsonPath=/config/initializerJson.json
    initializerJson: |
      [
        {
          "httpRequest": { "path": "/.*" },
          "httpForward": { "host": "upstream.default.svc.cluster.local", "port": 8080 },
          "chaos": {
            "errorStatus": 503,
            "errorProbability": 0.3,
            "retryAfter": "5",
            "latency": { "timeUnit": "MILLISECONDS", "value": 500 }
          },
          "times": { "unlimited": true }
        }
      ]
```

```bash
helm upgrade --install --namespace mockserver \
  -f values-chaos.yaml \
  mockserver helm/mockserver
```

The `/.*` request matcher means every path is forwarded to the upstream with chaos applied, so no separate `mockserver.proxyRemoteHost` is required. (If `app.persistence.enabled` is also `true`, drop the `mockserver.initializationJsonPath` line — the chart points the initialization path at the persisted-expectations file automatically.)

The `chaos` block supports probabilistic errors, latency, connection drops, count-based and time-based outage windows, response-body corruption, slow (dribbled) responses, and a stateful request quota. See the [Chaos Testing & Fault Injection](https://www.mock-server.com/mock_server/chaos_testing.html) reference and the [Chaos Proxy in Kubernetes](https://www.mock-server.com/mock_server/chaos_testing_kubernetes.html) guide (reverse-proxy, egress/forward-proxy, and sidecar patterns, plus CA-trust setup for TLS interception).

### MockServer URL

#### Local Kubernetes Cluster (i.e. [minikube](https://github.com/kubernetes/minikube), [microk8s](https://microk8s.io/))

If the `service` type hasn't been modified the following will provide the MockServer URL from outside the cluster.

```bash
export NODE_PORT=$(kubectl get -n mockserver -o jsonpath="{.spec.ports[0].nodePort}" services mockserver)
export NODE_IP=$(kubectl get nodes -n mockserver -o jsonpath="{.items[0].status.addresses[0].address}")
export MOCKSERVER_HOST=$NODE_IP:$NODE_PORT
echo http://$MOCKSERVER_HOST
```

To test the installation the following `curl` command should return the ports MockServer is bound to:

```bash
curl -v -X PUT http://$MOCKSERVER_HOST/mockserver/status
```

#### Docker for Desktop

[Docker Desktop](https://www.docker.com/products/docker-desktop) automatically exposes **LoadBalancer** services.  
On macOS Docker Desktop runs inside a lightweight VM, so the node IP address is not reachable from the host; the only way to call services is via the exposed **LoadBalancer** service added by Docker Desktop.

To ensure that Docker for Desktop exposes MockServer update the service type to **LoadBalancer** using **--set service.type=LoadBalancer** and set the exposed port using **--set service.port=1080**, as follows:

```bash
helm upgrade --install --create-namespace --namespace mockserver --set service.type=LoadBalancer --set service.port=1080 mockserver oci://ghcr.io/mock-server/charts/mockserver
```

MockServer will then be reachable on **http://localhost:1080**

For **LoadBalancer** services it is possible to query kubernetes to programmatically determine the MockServer base URL as follows:

```bash
export SERVICE_IP=$(kubectl get svc --namespace mockserver mockserver -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
export MOCKSERVER_HOST=$SERVICE_IP:1080
echo http://$MOCKSERVER_HOST
```

#### Outside Remote Kubernetes Cluster (i.e. Azure AKS, AWS EKS, etc)

```bash
kubectl -n mockserver port-forward svc/mockserver 1080:1080 &
export MOCKSERVER_HOST=127.0.0.1:1080
echo http://$MOCKSERVER_HOST
```

#### Inside Kubernetes Cluster

If a [DNS server](https://kubernetes.io/docs/concepts/services-networking/service/#dns) has been installed in the Kubernetes cluster the following DNS name should be available `mockserver.<namespace>.svc.cluster.local`, i.e. `mockserver.mockserver.svc.cluster.local`

### Available Versions

Every released version is published to GHCR — the canonical, always-current list is the
[GHCR package page](https://github.com/orgs/mock-server/packages/container/package/charts%2Fmockserver)
(install any with `--version`). The legacy `.tgz` archives below are kept for the HTTP install method:

| Version | Chart Archive (legacy `.tgz`) |
|---------|---------------|
| 9.2.0 (latest) | [mockserver-9.2.0.tgz](https://www.mock-server.com/mockserver-9.2.0.tgz) |
| 6.1.0 | [mockserver-6.1.0.tgz](https://www.mock-server.com/mockserver-6.1.0.tgz) |
| 6.0.0 | [mockserver-6.0.0.tgz](https://www.mock-server.com/mockserver-6.0.0.tgz) |
| 5.15.0 | [mockserver-5.15.0.tgz](https://www.mock-server.com/mockserver-5.15.0.tgz) |
| 5.14.0 | [mockserver-5.14.0.tgz](https://www.mock-server.com/mockserver-5.14.0.tgz) |
| 5.13.2 | [mockserver-5.13.2.tgz](https://www.mock-server.com/mockserver-5.13.2.tgz) |
| 5.13.1 | [mockserver-5.13.1.tgz](https://www.mock-server.com/mockserver-5.13.1.tgz) |
| 5.13.0 | [mockserver-5.13.0.tgz](https://www.mock-server.com/mockserver-5.13.0.tgz) |
| 5.12.0 | [mockserver-5.12.0.tgz](https://www.mock-server.com/mockserver-5.12.0.tgz) |
| 5.11.2 | [mockserver-5.11.2.tgz](https://www.mock-server.com/mockserver-5.11.2.tgz) |
| 5.11.1 | [mockserver-5.11.1.tgz](https://www.mock-server.com/mockserver-5.11.1.tgz) |
| 5.11.0 | [mockserver-5.11.0.tgz](https://www.mock-server.com/mockserver-5.11.0.tgz) |

### Helm Delete

To completely remove the chart:

```bash
helm uninstall mockserver --namespace mockserver
```

> **Note:** if you enabled persistence, the chart-managed PersistentVolumeClaim is **not** removed by `helm uninstall` — delete it manually (`kubectl -n mockserver delete pvc -l release=mockserver`) if you want to discard the persisted data.
