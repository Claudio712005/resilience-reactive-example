#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:8081}"
FAILURES=0

check() {
  local description="$1"
  shift
  if "$@"; then
    printf '  \033[32mok\033[0m   %s\n' "$description"
  else
    printf '  \033[31mfail\033[0m %s\n' "$description"
    FAILURES=$((FAILURES + 1))
  fi
}

status_is() {
  local expected="$1" url="$2"
  local actual
  actual=$(curl -sS -o /dev/null -w '%{http_code}' "$url" || echo 000)
  [ "$actual" = "$expected" ]
}

body_contains() {
  local needle="$1" url="$2"
  curl -sS "$url" | grep -q "$needle"
}

header_present() {
  local header="$1" url="$2"
  curl -sS -D - -o /dev/null "$url" | grep -qi "^$header:"
}

metric_present() {
  local metric="$1"
  curl -sS "$BASE_URL/actuator/prometheus" | grep -q "^$metric"
}

echo "Smoke test contra $BASE_URL"

echo
echo "Disponibilidade"
check "readiness responde 200" status_is 200 "$BASE_URL/actuator/health/readiness"
check "liveness responde 200" status_is 200 "$BASE_URL/actuator/health/liveness"
check "wiremock respondendo" status_is 200 "$WIREMOCK_URL/__admin/health"

echo
echo "Caminho feliz"
check "view do produto responde 200" status_is 200 "$BASE_URL/api/v1/products/SKU-NOTE-0001"
check "preco vem do pricing-service" body_contains '"source":"pricing-service"' "$BASE_URL/api/v1/products/SKU-NOTE-0001"
check "estoque vem do inventory-service" body_contains '"source":"inventory-service"' "$BASE_URL/api/v1/products/SKU-NOTE-0001"
check "view marcada como saudavel" body_contains '"fullyHealthy":true' "$BASE_URL/api/v1/products/SKU-NOTE-0001"
check "segunda chamada servida do cache" body_contains '"servedFrom":"redis-cache"' "$BASE_URL/api/v1/products/SKU-NOTE-0001"
check "listagem paginada responde 200" status_is 200 "$BASE_URL/api/v1/products?page=0&size=5"

echo
echo "Rastreabilidade"
check "requestId devolvido no header" header_present "X-Request-Id" "$BASE_URL/api/v1/products/SKU-NOTE-0001"
check "correlationId devolvido no header" header_present "X-Correlation-Id" "$BASE_URL/api/v1/products/SKU-NOTE-0001"
check "transactionId devolvido no header" header_present "X-Transaction-Id" "$BASE_URL/api/v1/products/SKU-NOTE-0001"

echo
echo "Degradacao controlada"
check "SKU lento responde 200 degradado" body_contains '"degradedSources"' "$BASE_URL/api/v1/products/SKU-SLOW-9001"
check "SKU lento usa preco base do catalogo" body_contains '"source":"catalog-base-price"' "$BASE_URL/api/v1/products/SKU-SLOW-9001"
check "SKU com falha responde 200 degradado" status_is 200 "$BASE_URL/api/v1/products/SKU-FAIL-9002"
check "SKU inexistente responde 404" status_is 404 "$BASE_URL/api/v1/products/SKU-NAO-EXISTE"

echo
echo "Redis como otimizador"
curl -sS -X POST "$BASE_URL/api/v1/chaos/redis?unavailable=true" >/dev/null
check "catalogo responde com redis fora" status_is 200 "$BASE_URL/api/v1/products/SKU-CAFE-0003"
check "resposta nao vem do cache com redis fora" body_contains '"servedFrom":"database-and-downstreams"' "$BASE_URL/api/v1/products/SKU-CAFE-0003"
curl -sS -X POST "$BASE_URL/api/v1/chaos/reset" >/dev/null

echo
echo "Banco como dependencia dura"
curl -sS -X POST "$BASE_URL/api/v1/chaos/database?unavailable=true" >/dev/null
check "catalogo responde 503 com banco fora" status_is 503 "$BASE_URL/api/v1/products/SKU-MON-0004"
curl -sS -X POST "$BASE_URL/api/v1/chaos/reset" >/dev/null

echo
echo "Metricas expostas"
check "estado do circuito publicado" metric_present "resilience4j_circuitbreaker_state"
check "eventos de retry publicados" metric_present "resilience4j_retry_calls_total"
check "slots de bulkhead publicados" metric_present "resilience4j_bulkhead_available_concurrent_calls"
check "permissoes de rate limiter publicadas" metric_present "resilience4j_ratelimiter_available_permissions"
check "chamadas de cache publicadas" metric_present "resilience4j_cache_calls_total"
check "fallbacks publicados" metric_present "catalog_resilience_fallback_total"
check "latencia da view publicada" metric_present "catalog_product_view_seconds"

echo
if [ "$FAILURES" -eq 0 ]; then
  printf '\033[32mSmoke test concluido sem falhas.\033[0m\n'
else
  printf '\033[31mSmoke test com %s falha(s).\033[0m\n' "$FAILURES"
  exit 1
fi
