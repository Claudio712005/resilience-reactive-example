SHELL := /usr/bin/env bash
IMAGE := catalog/resilience-reactive-example:local

.PHONY: help build test package compose-up compose-down compose-logs demo load kind-up kind-down k8s-render clean

help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

build: ## Compila o projeto
	./mvnw -B clean compile

test: ## Roda a suite de testes
	./mvnw -B test

package: ## Gera o jar executavel
	./mvnw -B clean package

compose-up: ## Sobe a stack completa com docker compose
	docker compose up -d --build

compose-down: ## Derruba a stack e remove volumes
	docker compose down -v

compose-logs: ## Acompanha os logs da aplicacao
	docker compose logs -f app

demo: ## Executa o roteiro de demonstracao de resiliencia
	./scripts/demo-resilience.sh

load: ## Gera carga para popular os dashboards
	./scripts/load-test.sh

kind-up: ## Cria o cluster kind e aplica os manifests
	./scripts/kind-up.sh

kind-down: ## Remove o cluster kind
	./scripts/kind-down.sh

k8s-render: ## Renderiza os manifests do kustomize
	kubectl kustomize .

clean: ## Limpa artefatos de build
	./mvnw -B clean
