#!/usr/bin/env bash
set -Eeuo pipefail

kind delete cluster --name catalog-resilience
