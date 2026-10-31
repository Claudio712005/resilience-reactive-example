#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
WIREMOCK_URL="${WIREMOCK_URL:-http://localhost:8081}"

banner() {
  printf '\n\033[1m==> %s\033[0m\n' "$1"
}

call() {
  local sku="$1"
  curl -sS -o /dev/null -w "  %{http_code} em %{time_total}s\n" \
    -H "X-Correlation-Id: demo-$(date +%s)-$RANDOM" \
    -H "X-Transaction-Id: txn-$sku" \
    "$BASE_URL/api/v1/products/$sku"
}

show() {
  local sku="$1"
  curl -sS -H "X-Correlation-Id: demo-show-$sku" "$BASE_URL/api/v1/products/$sku" \
    | python3 -m json.tool 2>/dev/null || true
}

banner "1. Caminho feliz com cache frio e depois quente"
show SKU-NOTE-0001
call SKU-NOTE-0001
call SKU-NOTE-0001

banner "2. Downstream lento: TimeLimiter corta e o fallback responde degradado"
show SKU-SLOW-9001

banner "3. Downstream com erro permanente: circuito abre apos a janela minima"
for _ in $(seq 1 15); do call SKU-FAIL-9002; done
curl -sS "$BASE_URL/actuator/circuitbreakers" | python3 -m json.tool 2>/dev/null | head -40

banner "4. Retry com backoff exponencial recupera o downstream instavel"
curl -sS -X POST "$WIREMOCK_URL/__admin/scenarios/reset" >/dev/null || true
show SKU-FLAKY-9003

banner "5. Redis indisponivel: a chamada cai para banco e downstreams"
curl -sS -X POST "$BASE_URL/api/v1/chaos/redis?unavailable=true" | python3 -m json.tool
call SKU-FONE-0002
call SKU-FONE-0002
curl -sS -X POST "$BASE_URL/api/v1/chaos/redis?unavailable=false" | python3 -m json.tool

banner "6. Banco indisponivel: a API responde 503 explicito"
curl -sS -X POST "$BASE_URL/api/v1/chaos/database?unavailable=true" | python3 -m json.tool
curl -sS -i "$BASE_URL/api/v1/products/SKU-MON-0004" | head -20
curl -sS -X POST "$BASE_URL/api/v1/chaos/reset" | python3 -m json.tool

banner "7. Metricas de resiliencia expostas ao Prometheus"
curl -sS "$BASE_URL/actuator/prometheus" \
  | grep -E "^resilience4j_(circuitbreaker_state|retry_calls_total|bulkhead_available|ratelimiter_available)|^catalog_resilience_fallback_total|^resilience4j_cache_calls_total" \
  | head -25
