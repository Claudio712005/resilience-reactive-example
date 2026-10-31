# Decisões de arquitetura

Registro das escolhas tomadas durante a construção do serviço, com alternativa considerada e consequência.

---

## D1 — R2DBC no lugar de Spring Data JPA

**Decisão:** o acesso ao PostgreSQL usa `spring-boot-starter-data-r2dbc` com `r2dbc-postgresql`.

**Por quê:** JPA/Hibernate é bloqueante. Usá-lo sob WebFlux obrigaria a empurrar cada query para um `boundedElastic`, o que anula o modelo de concorrência do Reactor, infla o número de threads e torna o backpressure inútil. O esqueleto original do projeto vinha com `spring-boot-starter-data-jpa` — foi substituído.

**Consequência:** sem lazy loading, sem `@Transactional` declarativo com semântica JPA, sem `Pageable` retornando `Page<T>` (a contagem é um endpoint separado). Em troca, o caminho de request é não bloqueante de ponta a ponta.

---

## D2 — Flyway via JDBC, apenas no boot

**Decisão:** migrações com Flyway sobre JDBC (`spring.flyway.url`), coexistindo com o datasource R2DBC da aplicação.

**Por quê:** Flyway não tem suporte R2DBC maduro. Migração é operação de inicialização, executada uma vez, fora do caminho de request — bloquear ali é aceitável e preferível a inicializar schema com scripts soltos.

**Consequência:** duas URLs de conexão no `application.yaml` apontando para o mesmo banco. O driver JDBC entra no classpath só para isso.

---

## D3 — Cache próprio em vez do módulo `resilience4j-cache`

**Decisão:** `ResilienceCache` implementa cache-aside em duas camadas (Caffeine local + Redis remoto), com as métricas publicadas sob o nome `resilience4j.cache.calls{kind, tier}`.

**Por quê:** o módulo `resilience4j-cache` depende de JSR-107 (JCache) e sua API é síncrona (`CheckedSupplier`), sem operador Reactor equivalente. Encaixá-lo exigiria bloquear. A implementação própria preserva a semântica (hit/miss/error) e o nome da métrica, de modo que os dashboards leem como se fosse nativo, mas é inteiramente não bloqueante.

**Consequência:** mais código sob nossa responsabilidade. Em troca, duas camadas de cache, TTL independente por camada e degradação silenciosa — coisas que o módulo original não entrega.

---

## D4 — Redis é otimizador, não dependência

**Decisão:** toda operação de Redis passa pelo pipeline `product-cache` (TimeLimiter 150 ms, circuit breaker próprio, bulkhead) e qualquer erro é convertido em `Mono.empty()`, caindo para banco + downstreams. O health indicator do Redis está **desligado** (`management.health.redis.enabled: false`).

**Por quê:** era requisito explícito. Se o Redis entrasse no `/actuator/health/readiness`, uma falha de cache tiraria todos os pods de rotação no Kubernetes — exatamente o oposto de resiliência.

**Consequência:** Redis fora significa mais latência e mais carga no banco e nos downstreams, nunca indisponibilidade. O operador percebe pelo painel de cache hit ratio e pelo contador `catalog_resilience_fallback_total{name="product-cache"}`, não por um pod reiniciando.

---

## D5 — Ordem dos operadores de resiliência

**Decisão:** `Retry( CircuitBreaker( RateLimiter( TimeLimiter( Bulkhead( chamada ))))) ` e, por fora de tudo, o `Timer` do Resilience4j.

**Por quê:** é a ordem recomendada pela documentação do Resilience4j. O retry por fora permite que uma nova tentativa reavalie o circuito; o rate limiter dentro do circuito evita gastar permissão em chamada que seria rejeitada; o bulkhead mais interno garante que o slot só fique ocupado durante a execução real.

**Consequência:** no Reactor a composição é de dentro para fora, então o código aplica `BulkheadOperator` primeiro e `RetryOperator` por último — a leitura do método `ResiliencePipeline.protect` parece invertida em relação à notação acima, mas produz exatamente esse aninhamento.

---

## D6 — Bulkhead semafórico, não `ThreadPoolBulkhead`

**Decisão:** `BulkheadOperator` com configuração de semáforo.

**Por quê:** `ThreadPoolBulkhead` só opera sobre `CompletableFuture`/`CompletionStage` e não possui operador Reactor. Usá-lo exigiria converter o `Mono` em future, perdendo cancelamento e backpressure.

**Consequência:** o isolamento é de concorrência, não de thread. O isolamento de thread é obtido pelos schedulers dedicados (D8).

---

## D7 — Erro 4xx não conta para o circuito nem gera retry

**Decisão:** respostas 4xx dos downstreams são mapeadas para `DownstreamClientException`, registrada em `ignore-exceptions` do circuit breaker e do retry. `CallNotPermittedException`, `RequestNotPermitted` e `BulkheadFullException` também estão em `ignore-exceptions` do retry.

**Por quê:** 4xx é erro de contrato — repetir produz o mesmo resultado e, pior, abre o circuito de um downstream saudável. Reexecutar uma chamada já rejeitada por circuito/rate limiter/bulkhead amplifica carga em sistema saturado.

**Consequência:** só timeout, erro de conexão e 5xx influenciam o estado do circuito. O SKU `SKU-TECL-0005` existe no seed justamente para demonstrar isso (o `rating-service` responde 404 e o circuito permanece fechado).

---

## D8 — Schedulers dedicados aplicados com `publishOn`, não `subscribeOn`

**Decisão:** três schedulers (`catalog-integration` e `catalog-persistence` bounded elastic, `catalog-enrichment` parallel), aplicados com `publishOn` imediatamente após o I/O — não com `subscribeOn` na cadeia inteira.

**Por quê:** a primeira versão usava `subscribeOn` nos adapters HTTP e no adapter R2DBC. Isso é enganoso: `subscribeOn` só move a chamada de `subscribe()` e o trabalho síncrono de montagem. WebClient e R2DBC são não bloqueantes, então o I/O continua sendo concluído no event loop do Netty independentemente do `subscribeOn` — o que precisava sair do event loop era a desserialização e o mapeamento, ou seja, o trabalho **posterior** à resposta. `publishOn` logo após `bodyToMono`/a query é o operador que realmente faz isso.

**Consequência:** o event loop volta a ficar livre após entregar os bytes, e os schedulers passam a representar isolamento real de CPU por domínio. O custo é uma troca de contexto por chamada — e é o motivo pelo qual a propagação de MDC precisou de `ThreadLocalAccessor` próprio (D9).

---

## D9 — Propagação de contexto via `ThreadLocalAccessor` próprio

**Decisão:** `TraceContext` é escrito no contexto do Reactor pelo `TraceContextWebFilter` e espelhado no MDC por um `ThreadLocalAccessor` registrado no `ContextRegistry`, com `Hooks.enableAutomaticContextPropagation()` ativo.

**Por quê:** MDC é `ThreadLocal`; com troca de scheduler os identificadores se perdem. O acessor próprio garante que os oito campos apareçam em todo log, independentemente da thread. Os mesmos campos também são declarados como W3C baggage, o que os propaga para os downstreams via header `baggage` além dos headers `X-*` explícitos.

**Consequência:** dupla escrita dos mesmos valores no MDC (pelo acessor e pela correlação de baggage do Micrometer) — inofensiva, pois as chaves são idênticas. Em troca, rastreabilidade determinística sem depender só do tracer.

---

## D10 — View degradada não vai para o cache

**Decisão:** o write-behind só acontece quando `serving.degradedSources` está vazio.

**Por quê:** gravar uma view montada com fallback congelaria o dado degradado por todo o TTL (5 min), propagando uma falha momentânea muito além da sua duração.

**Consequência:** durante uma indisponibilidade de downstream o cache hit ratio cai e o banco recebe mais carga. É deliberado: preferimos custo de latência a dado errado.

---

## D11 — PostgreSQL é a única dependência dura

**Decisão:** falha na fonte da verdade vira `CatalogUnavailableException` → HTTP 503. Falha de cache ou de qualquer downstream vira degradação.

**Por quê:** não existe fallback honesto para o dado mestre do produto. Responder com dado inventado seria pior do que responder 503.

**Consequência:** a disponibilidade do serviço está atrelada à do banco. Mitigações possíveis (réplica de leitura, cache de último recurso com TTL longo) ficaram fora do escopo deste exemplo.

---

## D12 — Registry Prometheus em vez de OTLP para métricas

**Decisão:** `micrometer-registry-prometheus` com scrape em `/actuator/prometheus`; export OTLP de métricas e traces desligado por padrão.

**Por quê:** o requisito era Grafana com dashboards prontos. O modelo pull do Prometheus dispensa coletor intermediário, o que mantém o Compose e o kind com menos peças. O tracing continua instrumentado via Micrometer Tracing + OpenTelemetry — o que alimenta `traceId`/`spanId` nos logs — apenas sem exportador remoto ativo.

**Consequência:** para enviar traces a um Tempo/Jaeger basta ligar `management.otlp.tracing.export.enabled` e apontar o endpoint. Nenhuma mudança de código.

---

## D13 — Testes: unitários e WireMock, sem Testcontainers

**Decisão:** 30 testes que rodam sem Docker (pipeline de resiliência, serviço de aplicação com fakes, adapter HTTP real contra WireMock embarcado, cache com mocks do template reativo, filtro de rastreio). A validação do stack completo fica em `scripts/smoke-test.sh` (asserções com exit code) e nos scripts `demo-resilience.sh` e `load-test.sh`.

**Por quê:** o Docker não estava disponível no ambiente de construção, e a versão de Testcontainers gerenciada pelo Spring Boot 4.1.2 é a linha 2.x, com API diferente da 1.x. Escrever um teste de integração que não pode ser executado seria entregar código não verificado — pior do que não entregar.

**Consequência:** a cobertura de integração real (Postgres + Redis + WireMock juntos) é feita por script, de forma observável mas não automatizada em CI. Adicionar um módulo de teste com Testcontainers 2.x é o próximo passo natural, com Docker disponível.

Complementando, a inicialização do contexto foi validada manualmente subindo o jar sem Postgres e sem Redis. Esse passo pagou-se de imediato: encontrou os dois defeitos descritos em **D18** e **D19**, que nenhum teste unitário pegaria porque nenhum deles levanta o contexto Spring.

---

## D14 — Java 21 em vez de Java 25

**Decisão:** `java.version` foi alterado de 25 para 21.

**Por quê:** o JDK instalado na máquina é o 21.0.10. Manter 25 tornaria o projeto não compilável localmente. Spring Boot 4.1 suporta 17+.

**Consequência:** sem os recursos exclusivos do 25. Subir para 25 depende apenas de trocar essa propriedade e o JDK base do `Dockerfile`.

---

## D15 — `kustomization.yaml` na raiz do repositório

**Decisão:** os manifests ficam em `k8s/base/`, mas o kustomization que gera os ConfigMaps está na raiz (`kubectl apply -k .`).

**Por quê:** o kustomize recusa referenciar arquivos acima do diretório do kustomization. Os mapeamentos do WireMock e os dashboards do Grafana vivem em `docker/`, consumidos tanto pelo Compose quanto pelo Kubernetes. A alternativa seria duplicar esses arquivos dentro de `k8s/`, criando duas fontes de verdade que divergem com o tempo.

**Consequência:** o comando de apply roda da raiz, não de `k8s/`. Em troca, dashboards e stubs têm uma única cópia.

---

## D16 — Domínio: catálogo de produtos

**Decisão:** entre as duas opções propostas (catálogo de produtos ou gerenciador de insumos), ficou o catálogo.

**Por quê:** o catálogo justifica naturalmente três integrações externas com criticidade diferente (preço, estoque, avaliação), o que é exatamente o cenário onde timeout por serviço, fallback diferenciado e circuitos independentes fazem sentido. Avaliação é descartável, estoque é importante, preço é crítico — e essa gradação aparece na configuração.

**Consequência:** o modelo de dados é simples de propósito; o peso do exemplo está na política de resiliência, não na riqueza do domínio.

---

## D17 — Injeção de falha por endpoint em runtime

**Decisão:** `ChaosSwitch` + `/api/v1/chaos/*` permitem derrubar Redis e banco, e injetar latência, sem reiniciar nada.

**Por quê:** demonstrar degradação exige provocar falha. Derrubar o container do Redis funciona, mas é lento e não permite latência controlada.

**Consequência:** o endpoint não tem autenticação neste exemplo. Em ambiente real ele precisa ficar atrás de autorização ou de um profile exclusivo de laboratório.

---

## D18 — Jackson 3 (`tools.jackson`) para serializar o que vai ao Redis

**Decisão:** `ResilienceCache` injeta `tools.jackson.databind.json.JsonMapper`, não `com.fasterxml.jackson.databind.ObjectMapper`.

**Por quê:** Spring Boot 4 traz Jackson 2 e Jackson 3 no classpath, mas o `JacksonAutoConfiguration` expõe apenas o mapper do Jackson 3 (`tools.jackson...JsonMapper`). A primeira versão injetava o `ObjectMapper` do Jackson 2 — não existe bean desse tipo, e a aplicação **não subia**. O teste unitário não pegou porque construía o mapper na mão; só a subida real do contexto revelou.

Há um segundo efeito: o Jackson 2 presente no classpath está sem `jackson-datatype-jsr310`, então serializar `java.time.Instant` falha. Como `ProductView` tem `createdAt`, e como o cache engole erro de serialização por projeto, o Redis nunca teria gravado nada — uma falha silenciosa. O Jackson 3 trata `java.time` nativamente.

**Consequência:** o `ProductViewSerializationTest` foi adicionado para travar esse comportamento, fazendo round-trip da view exatamente como ela vai para o Redis.

---

## D19 — Lettuce sem pool de conexões

**Decisão:** `spring.data.redis.lettuce.pool` removido da configuração.

**Por quê:** ativar o pool exige `commons-pool2` no classpath; sem ele a criação do `LettuceConnectionFactory` lança `NoClassDefFoundError` e a aplicação não sobe. Mais importante: pool de conexões não faz sentido no uso reativo do Lettuce, que multiplexa comandos sobre uma única conexão — pooling existe para o uso imperativo/bloqueante.

**Consequência:** uma dependência a menos e menos configuração. Se algum dia o código passar a usar a API blocante do Redis, o pool (e o `commons-pool2`) voltam a ser necessários.

---

## D20 — `max-wait-duration: 0` em todos os bulkheads

**Decisão:** nenhuma instância de bulkhead espera por permissão.

**Por quê:** o `SemaphoreBulkhead` do Resilience4j implementa a espera com `Semaphore.tryAcquire(long, TimeUnit)`, que **bloqueia a thread chamadora**. No `BulkheadOperator` essa aquisição acontece na thread que assina o fluxo — ou seja, um event loop do Netty. A configuração inicial tinha `50ms` e `100ms` nas instâncias de banco, o que bloquearia o event loop sob saturação: exatamente o inverso do objetivo.

**Consequência:** sob saturação a rejeição é imediata (`BulkheadFullException` → HTTP 429), sem fila. Quem precisa de enfileiramento deve fazê-lo com operadores do Reactor (`limitRate`, `onBackpressureBuffer`), não com o bulkhead.

---

## D21 — Uma instância de resiliência por operação, sem timeout duplicado

**Decisão:** a listagem paginada ganhou sua própria instância `product-database-list` (TimeLimiter 3 s), e os timeouts que existiam em `catalog.data-access.*` foram removidos. O TimeLimiter de cada instância é a única autoridade sobre o prazo da chamada.

**Por quê:** leitura por SKU e listagem paginada tinham perfis de latência diferentes e compartilhavam a mesma instância, o que forçava um `.timeout()` manual por cima do TimeLimiter — dois números para manter em sincronia, com o menor sempre ganhando de forma não óbvia. Também foram removidos `call-timeout` e `fail-fast-on-open-circuit` dos endpoints, que nunca eram lidos pelo código.

**Consequência:** sete instâncias em vez de seis, e o prazo de cada operação se lê em um único lugar. O `response-timeout` do Netty continua existindo como guarda de nível mais baixo, sempre folgado em relação ao TimeLimiter.

---

## D22 — UID numérico na imagem e no pod

**Decisão:** o `Dockerfile` cria o usuário com `-u 10001` e declara `USER 10001:10001`; o Deployment declara `runAsUser: 10001` junto de `runAsNonRoot: true`.

**Por quê:** com `runAsNonRoot: true` e um `USER` por nome (`USER catalog`), o kubelet não consegue provar que o usuário não é root e o container falha com `CreateContainerConfigError`. A versão inicial tinha exatamente essa combinação.

**Consequência:** o pod também roda com `readOnlyRootFilesystem: true`, o que exigiu um `emptyDir` montado em `/tmp` para a JVM.
