#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
CONCURRENCY="${CONCURRENCY:-40}"
DURATION_SECONDS="${DURATION_SECONDS:-60}"
SKUS=(SKU-NOTE-0001 SKU-FONE-0002 SKU-CAFE-0003 SKU-MON-0004 SKU-TECL-0005 SKU-SLOW-9001 SKU-FAIL-9002)

worker() {
  local deadline=$(( $(date +%s) + DURATION_SECONDS ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local sku="${SKUS[$RANDOM % ${#SKUS[@]}]}"
    curl -sS -o /dev/null \
      -H "X-Correlation-Id: load-$RANDOM" \
      -H "X-Transaction-Id: load-txn-$RANDOM" \
      "$BASE_URL/api/v1/products/$sku" || true
  done
}

echo "Gerando carga por ${DURATION_SECONDS}s com ${CONCURRENCY} workers em ${BASE_URL}"
for _ in $(seq 1 "$CONCURRENCY"); do
  worker &
done
wait
echo "Carga concluida. Verifique os dashboards do Grafana."
