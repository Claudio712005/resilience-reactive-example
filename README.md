# resilience-reactive-example

Serviço de referência de **catálogo de produtos** reativo, construído para demonstrar resiliência de ponta a ponta com Spring WebFlux, Project Reactor e Resilience4j — com Redis atuando como **otimizador**, nunca como dependência.

Stack: Java 21 · Spring Boot 4.1.2 · Spring WebFlux · Project Reactor · Resilience4j 2.4.0 · R2DBC PostgreSQL · Redis (Lettuce reativo) · WireMock · Prometheus · Grafana · Docker Compose · Kubernetes (kind).

---

## 1. O problema que o projeto resolve

Servir a visão completa de um produto (dado mestre + preço + estoque + avaliações) com latência baixa e **sem que a falha de um componente derrube a resposta**.

```
                           ┌──────────────────────────────┐
  GET /api/v1/products/SKU │  TraceContextWebFilter       │  gera/propaga requestId,
 ─────────────────────────►│  (requestId, correlationId,  │  correlationId, transactionId
                           │   transactionId, trace/span) │  e devolve nos headers
                           └──────────────┬───────────────┘
                                          ▼
                           ┌──────────────────────────────┐
                           │  ProductCatalogService       │
                           └──────────────┬───────────────┘
                                          ▼
                     ┌────────────────────────────────────────┐
                     │ 1. ResilienceCache (L1 Caffeine + L2   │  hit  ─► resposta
                     │    Redis) protegido por CB/TL/BH       │
                     └───────────────────┬────────────────────┘
                                         │ miss ou Redis falhando
                                         ▼
                     ┌────────────────────────────────────────┐
                     │ 2. PostgreSQL via R2DBC                │  fonte da verdade
                     │    (CB + TimeLimiter + Bulkhead + RL)  │  falha aqui = 503
                     └───────────────────┬────────────────────┘
                                         ▼
          ┌──────────────────────────────┼──────────────────────────────┐
          ▼                              ▼                              ▼
  pricing-service               inventory-service               rating-service
  (WebClient + CB + RL +        (WebClient + CB + RL +          (WebClient + CB + RL +
   BH + TL 800ms + retry         BH + TL 600ms + retry           BH + TL 400ms + retry
   exponencial)                  exponencial)                     exponencial)
          │                              │                              │
          └──────── fallback degradado ──┴──── fallback degradado ───────┘
                                         ▼
                     ┌────────────────────────────────────────┐
                     │ 3. Composição em paralelo (Mono.zip em │
                     │    scheduler dedicado) + write-behind  │
                     │    no cache apenas se 100% saudável    │
                     └────────────────────────────────────────┘
```

**Regra central de degradação:** Postgres é a única dependência dura. Redis e os três downstreams podem cair — a resposta continua sendo entregue, marcada em `serving.degradedSources`.

---

## 2. Padrões de resiliência e parametrização por serviço

Todas as instâncias são configuradas por nome em `src/main/resources/application.yaml` e aplicadas na ordem canônica do Resilience4j:

```
Retry( CircuitBreaker( RateLimiter( TimeLimiter( Bulkhead( chamada ))))) ─► Timer (métrica)
```

| Instância | TimeLimiter | Bulkhead | RateLimiter | Retry | Timeout de rede | Circuito abre em |
|---|---|---|---|---|---|---|
| `pricing-service` | 800 ms | 30 concorrentes | 200/s | 3 tentativas, backoff exp. 50 ms ×2 ± 50% | connect 200 ms / response 900 ms | 50% falha ou 60% slow (>800 ms) |
| `inventory-service` | 600 ms | 40 concorrentes | 300/s | 3 tentativas | connect 200 ms / response 700 ms | 50% falha ou 60% slow (>600 ms) |
| `rating-service` | 400 ms | 20 concorrentes | 150/s | 2 tentativas, backoff 30 ms | connect 150 ms / response 500 ms | 60% falha (menos crítico) |
| `product-database-read` | 1500 ms | 60 concorrentes (espera 50 ms) | 1000/s | 2 tentativas | pool R2DBC: acquire 2 s | 50% falha, janela de 50 |
| `product-database-write` | 2500 ms | 20 concorrentes (espera 100 ms) | 100/s | sem retry (escrita não idempotente) | — | 50% falha |
| `product-cache` (Redis) | 150 ms | 100 concorrentes | 5000/s | sem retry | timeout de comando 150 ms | 40% falha ou slow >150 ms |

Pontos de projeto relevantes:

- **Backoff exponencial com jitter** (`randomized-wait-factor: 0.5`) evita retry storm sincronizado.
- **4xx não é falha de infraestrutura:** respostas de cliente viram `DownstreamClientException`, que está em `ignore-exceptions` do circuit breaker e do retry. Só 5xx, timeout e erro de conexão contam para abrir o circuito.
- **`CallNotPermittedException`, `RequestNotPermitted` e `BulkheadFullException` nunca são reexecutadas** — estão em `ignore-exceptions` do retry, evitando amplificar carga em sistema já saturado.
- **Bulkhead semafórico** (não thread pool): é o único compatível com fluxo não bloqueante.
- **Sem retry na escrita e no cache**, por escolha explícita: escrita não é idempotente e retry em cache só adiciona latência ao caminho de fallback.
- **Cache não é gravado quando a view está degradada**, para não congelar dados de fallback pelo TTL inteiro.

---

## 3. Multithread e operações não bloqueantes

- Toda a cadeia é `Mono`/`Flux`; não há `block()` no caminho de request.
- Três schedulers dedicados e dimensionados (`SchedulerConfig`), para que a saturação de um domínio não contamine o event loop do Netty:
  - `catalog-integration` (bounded elastic) — chamadas HTTP de saída;
  - `catalog-persistence` (bounded elastic) — acesso R2DBC;
  - `catalog-enrichment` (parallel) — composição/CPU.
- O enriquecimento dos três downstreams roda em **paralelo real** via `Mono.zip`, cada ramo com `subscribeOn` no scheduler de integração.
- `GET /api/v1/products/views?skus=a,b,c` usa `Flux.parallel().runOn(...)` para resolver vários SKUs simultaneamente.
- Propagação de contexto entre threads via `Hooks.enableAutomaticContextPropagation()` + `ThreadLocalAccessor` próprio, o que mantém o MDC correto mesmo depois de troca de scheduler.
- Logging assíncrono (`AsyncAppender` com `neverBlock=true`) para que I/O de log não bloqueie thread reativa.

---

## 4. Observabilidade

### Identificadores de rastreabilidade

Todo log carrega, via MDC: `traceId`, `spanId`, `requestId`, `correlationId`, `transactionId`, `sessionId`, `originService` e `operation`.

- `traceId`/`spanId` vêm do Micrometer Tracing + OpenTelemetry.
- `requestId`, `correlationId`, `transactionId`, `sessionId` são lidos dos headers `X-Request-Id`, `X-Correlation-Id`, `X-Transaction-Id`, `X-Session-Id` — ou gerados quando ausentes — e **devolvidos na resposta**.
- Os mesmos identificadores são propagados para os downstreams em headers explícitos (`TracePropagationExchangeFilter`) e como W3C baggage.

Formato de console (`logback-spring.xml`):

```
2026-10-31T12:04:11.482-03:00 INFO  [catalog-integration-3] [app=resilience-reactive-example trace=4f1c… span=9ab3… request=4d2f… correlation=corr-456 transaction=txn-789 session=anonymous origin=checkout-bff operation=GET /api/v1/products/SKU-NOTE-0001] c.c.r.o.TracePropagationExchangeFilter - downstream call started service=pricing-service method=GET uri=http://wiremock:8080/pricing/v1/quotes/SKU-NOTE-0001
```

Perfil `json` (usado no Kubernetes) troca o appender para `LogstashEncoder`, com os mesmos campos como chaves de primeiro nível.

### Métricas e dashboards

`/actuator/prometheus` expõe, entre outras:

- `resilience4j_circuitbreaker_state`, `_failure_rate`, `_slow_call_rate`, `_calls_seconds_count`, `_not_permitted_calls_total`
- `resilience4j_retry_calls_total`, `resilience4j_timelimiter_calls_total`
- `resilience4j_bulkhead_available_concurrent_calls`, `resilience4j_ratelimiter_available_permissions`
- `resilience4j_timer_calls_seconds_*` (latência por componente protegido)
- `resilience4j_cache_calls_total{kind=hit|miss|error, tier=local|remote}`
- `catalog_resilience_fallback_total{name, exception, circuit_state}`
- `catalog_product_view_seconds_*{served_from, cache_hit, degraded}`
- `http_client_requests_seconds_*`, `reactor_netty_connection_provider_*`, `r2dbc_pool_*`

Dois dashboards já provisionados no Grafana (pasta **Resilience**):

| Dashboard | Conteúdo |
|---|---|
| `Catálogo · Resiliência (Resilience4j)` | estado e taxa de falha dos circuitos, calls por resultado, rejeições, slots de bulkhead, permissões de rate limiter, retries por resultado, time limiter, fallbacks acionados, p95 por componente |
| `Catálogo · Runtime reativo, cache e integrações` | cache hit ratio, views por origem, views degradadas, latência p50/p95/p99 da view, WebClient por serviço e status, HTTP server, pool Netty, pool R2DBC, threads/CPU |

---

## 5. Como executar

### 5.1 Docker Compose (caminho recomendado)

```bash
docker compose up -d --build
```

| Serviço | URL |
|---|---|
| API | http://localhost:8080/api/v1/products/SKU-NOTE-0001 |
| Actuator | http://localhost:8080/actuator/health |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin/admin) |
| WireMock admin | http://localhost:8081/__admin |

Derrubar: `docker compose down -v`.

### 5.2 Local (JVM na máquina, infraestrutura em containers)

```bash
docker compose up -d postgres redis wiremock
./mvnw spring-boot:run
```

Os defaults de `application.yaml` já apontam para `localhost` (Postgres 5432, Redis 6379, WireMock 8081).

### 5.3 Kubernetes local (kind)

```bash
./scripts/kind-up.sh      # cria cluster, builda a imagem, carrega no kind e aplica os manifests
```

| Serviço | URL |
|---|---|
| API | http://localhost:30080/api/v1/products/SKU-NOTE-0001 |
| Prometheus | http://localhost:30900 |
| Grafana | http://localhost:30300 |

Remover: `./scripts/kind-down.sh`. Renderizar os manifests sem aplicar: `kubectl kustomize .`

O cluster tem 1 control-plane + 2 workers, a aplicação sobe com 2 réplicas, `topologySpreadConstraints`, `PodDisruptionBudget`, HPA de CPU (2→6) e probes separadas de liveness/readiness/startup. O health do Redis está **desligado de propósito**: Redis fora não pode tornar o pod _not ready_.

---

## 6. Endpoints

| Método | Rota | Descrição |
|---|---|---|
| `GET` | `/api/v1/products/{sku}` | view composta (produto + preço + estoque + avaliações + metadados de serving) |
| `GET` | `/api/v1/products/views?skus=A,B,C` | resolve vários SKUs em paralelo |
| `GET` | `/api/v1/products?page=0&size=20` | listagem paginada |
| `GET` | `/api/v1/products/count` | total de produtos ativos |
| `POST` | `/api/v1/products` | cadastro (valida e invalida o cache do SKU) |
| `DELETE` | `/api/v1/products/{sku}/cache` | invalida o cache do SKU |
| `GET/POST` | `/api/v1/chaos`, `/chaos/redis`, `/chaos/database`, `/chaos/reset` | injeção de falha em runtime |
| `GET` | `/actuator/health`, `/actuator/prometheus`, `/actuator/circuitbreakers`, `/actuator/circuitbreakerevents` | operação |

Mapeamento de erro → status: `404` SKU inexistente, `409` SKU duplicado, `400` payload inválido, `429` rate limiter/bulkhead, `503` circuito aberto ou banco indisponível, `504` timeout. Toda resposta de erro traz `requestId` e `correlationId` no corpo.

### SKUs de laboratório

| SKU | Comportamento |
|---|---|
| `SKU-NOTE-0001`, `SKU-FONE-0002`, `SKU-CAFE-0003`, `SKU-MON-0004` | caminho feliz |
| `SKU-TECL-0005` | `rating-service` responde 404 → prova que 4xx não abre circuito |
| `SKU-SLOW-9001` | pricing 3 s e inventory 2 s → TimeLimiter corta, resposta degradada |
| `SKU-FAIL-9002` | pricing 500 e rating 503 → circuito abre após a janela mínima |
| `SKU-FLAKY-9003` | falha nas duas primeiras chamadas e recupera → prova o backoff exponencial |

---

## 7. Roteiro de demonstração

```bash
./scripts/demo-resilience.sh     # 7 cenários: cache, timeout, circuito, retry, Redis fora, banco fora, métricas
./scripts/load-test.sh           # carga para popular os dashboards (CONCURRENCY/DURATION_SECONDS configuráveis)
```

Exemplo de degradação controlada do Redis:

```bash
curl -X POST "http://localhost:8080/api/v1/chaos/redis?unavailable=true"
curl "http://localhost:8080/api/v1/products/SKU-FONE-0002"   # responde normalmente, via banco + downstreams
curl -X POST "http://localhost:8080/api/v1/chaos/reset"
```

---

## 8. Testes

```bash
./mvnw test
```

28 testes, sem necessidade de Docker:

| Suíte | O que cobre |
|---|---|
| `ResiliencePipelineTest` | retry com backoff exponencial, `ignore-exceptions`, abertura do circuito, corte do TimeLimiter, rejeição de bulkhead e de rate limiter, métrica de fallback |
| `ProductCatalogServiceTest` | cache-aside, serving de cache sem tocar na origem, degradação por downstream, degradação total, 404, tradução para `CatalogUnavailableException`, cache quebrado, resolução paralela |
| `PricingHttpAdapterTest` | HTTP real contra WireMock: mapeamento, propagação de headers de rastreio, recuperação por retry, 4xx sem retry, timeout |
| `ResilienceCacheTest` | promoção L2→L1, degradação silenciosa em falha do Redis, chaos switch, miss, write-through com TTL, cache desligado |
| `TraceContextWebFilterTest` | reaproveitamento de identificadores recebidos e geração/eco dos ausentes |

O stack completo (Compose/kind) é validado pelos scripts de demonstração e carga, não por Testcontainers — ver decisão **D7** em [`docs/DECISOES.md`](docs/DECISOES.md).

---

## 9. Estrutura

```
src/main/java/com/clau/resiliencereactiveexample
├── api                  controllers REST, DTOs, handler de exceções, chaos
├── application          ProductCatalogService (orquestração e política de degradação)
├── domain               records de domínio e ports (hexagonal)
├── infrastructure
│   ├── persistence      R2DBC (repository + adapter com resiliência)
│   ├── cache            ResilienceCache (Caffeine + Redis) e adapter de porta
│   ├── http             adapters WebClient dos três downstreams
│   └── chaos            ChaosSwitch
├── observability        TraceContext, filtros de entrada/saída, MDC accessor
├── resilience           ResiliencePipeline e factory (composição dos operadores)
└── config               properties por serviço, schedulers, WebClient, métricas

docker/                  compose assets: wiremock, prometheus, grafana (datasource + dashboards)
k8s/                     kind-cluster.yaml e manifests (base) aplicados via kustomize da raiz
scripts/                 kind-up/down, demo-resilience, load-test
docs/DECISOES.md         registro das decisões de arquitetura
```

---

## 10. Decisões de arquitetura

Estão registradas em [`docs/DECISOES.md`](docs/DECISOES.md), incluindo o porquê de R2DBC em vez de JPA, de não usar o módulo `resilience4j-cache`, da ordem dos operadores, do health check do Redis desligado e da estratégia de testes.
