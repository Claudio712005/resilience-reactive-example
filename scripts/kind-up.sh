#!/usr/bin/env bash
set -Eeuo pipefail

CLUSTER_NAME="catalog-resilience"
IMAGE="catalog/resilience-reactive-example:local"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"

if ! kind get clusters | grep -qx "$CLUSTER_NAME"; then
  kind create cluster --config k8s/kind-cluster.yaml
fi

docker build -t "$IMAGE" .
kind load docker-image "$IMAGE" --name "$CLUSTER_NAME"

kubectl apply -k .
kubectl -n catalog rollout status deployment/postgres --timeout=180s
kubectl -n catalog rollout status deployment/redis --timeout=120s
kubectl -n catalog rollout status deployment/wiremock --timeout=120s
kubectl -n catalog rollout status deployment/catalog-app --timeout=300s
kubectl -n catalog rollout status deployment/prometheus --timeout=180s
kubectl -n catalog rollout status deployment/grafana --timeout=180s

cat <<MSG

Cluster pronto.
  API         http://localhost:30080/api/v1/products/SKU-NOTE-0001
  Actuator    http://localhost:30080/actuator/health
  Prometheus  http://localhost:30900
  Grafana     http://localhost:30300 (admin/admin)
MSG
