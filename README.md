# Framework forward headers strategy

In Spring Cloud Gateway (WebFlux), when `server.forward-headers-strategy=framework`,
the RFC 7239 `Forwarded` header sent downstream loses the original host.

## The issue

With `server.forward-headers-strategy=framework`, Spring Boot registers a
`ForwardedHeaderTransformer`. The transformer rewrites the request URI, remote address and local
address from the incoming forwarded headers, then removes those headers. It does not change the
`Host` header (or any other).

With `spring.cloud.gateway.server.webflux.forwarded.enabled=true`, Spring Cloud Gateway's
`ForwardedHeadersFilter` adds a new `Forwarded` element for the downstream request:

- `proto` comes from the request URI, which has been rewritten by the transformer.
- `for` comes from the remote address, which has been rewritten by the transformer.
- `host` comes from the `Host` header (`original.getFirst(HttpHeaders.HOST)`), which has not been
  rewritten.

The incoming `Forwarded` header has already been removed by the transformer, so the gateway cannot
preserve it either. The downstream service therefore receives the gateway's own `Host` header in
place of the original host.

With `server.forward-headers-strategy=native`, the gateway's own element has the same flaw, but
Reactor Netty does not remove the incoming `Forwarded` header. The gateway keeps the trusted
incoming element ahead of its own, and downstream Spring applications read the first element, so
the original host survives.

## Versions

| Library                              | Version  |
|--------------------------------------|----------|
| Spring Boot                          | 4.1.1    |
| Spring Cloud                         | 2025.1.3 |
| Spring Cloud Gateway Server WebFlux  | 5.0.3    |
| Spring Framework (`spring-web`)      | 7.0.9    |
| Reactor Netty (`reactor-netty-http`) | 1.3.7    |

Java 25 is required.

## Test setup

The gateway (`src/main/resources/application.yaml`) trusts all proxies, emits only RFC
`Forwarded` headers (`x-forwarded.enabled: false`), and routes every request to
`${downstream.uri}`.

`ForwardedHeaderTests` starts a `RecordingServer` (a JDK `HttpServer` that records request
headers) as the downstream service, then sends this request to the gateway:

```http
GET /test HTTP/1.1
Host: localhost:<gateway port>
Forwarded: proto=https;host=example.com;for=192.0.2.10
```

The test asserts that the first `Forwarded` element received downstream contains `proto=https`
and `host=example.com`. This is the element that Spring's `ForwardedHeaderUtils` uses to resolve
the original request.

The same test runs in two nested classes:

| Test class                               | Strategy    | Expected result              |
|------------------------------------------|-------------|------------------------------|
| `ForwardedHeaderTests$FrameworkStrategy` | `framework` | Fails (reproduces the issue) |
| `ForwardedHeaderTests$NativeStrategy`    | `native`    | Passes (control)             |

## Running the tests

Run both strategies:

```sh
./mvnw test -Dtest=ForwardedHeaderTests
```

The build fails because `FrameworkStrategy` fails. That failure is the reproduction.

Run a single strategy:

```sh
./mvnw test -Dtest='ForwardedHeaderTests$FrameworkStrategy'
./mvnw test -Dtest='ForwardedHeaderTests$NativeStrategy'
```

Each test logs the `Forwarded` header received downstream. Example output:

```text
FrameworkStrategy downstream Forwarded: [proto=https;host="localhost:56092";for="192.0.2.10:56095"]
NativeStrategy downstream Forwarded: [proto=https;host=example.com;for=192.0.2.10, proto=https;host="localhost:56120";for="192.0.2.10:56123"]
```

With `framework`, `proto` and `for` reflect the original request, but `host` is the gateway's own
`Host` header. With `native`, the original element is preserved ahead of the gateway's element.

## Running the gateway manually

Start a downstream service that echoes request headers on port 8081 (or set `downstream.uri`),
then start the gateway on port 8080:

```sh
./mvnw spring-boot:run
```

Send a request with a `Forwarded` header and inspect what the downstream service receives:

```sh
curl -v http://localhost:8080/test -H 'Forwarded: proto=https;host=example.com;for=192.0.2.10'
```

To compare with the native strategy:

```sh
SERVER_FORWARDHEADERSSTRATEGY=native ./mvnw spring-boot:run
```

---

🤖 Assisted by Claude Code
