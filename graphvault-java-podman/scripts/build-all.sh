#!/usr/bin/env bash
# Convenience script: builds every Java service's jar locally with Maven before podman-compose
# builds the actual container images. Not strictly required (each Containerfile does its own
# `mvn clean package` in its build stage) -- this just lets you catch a compile error in
# seconds locally instead of waiting for a container build to fail.
set -euo pipefail

SERVICES=(asset-service titles-dgs artwork-dgs availability-dgs graphql-gateway)

for service in "${SERVICES[@]}"; do
  echo "=== Building $service ==="
  (cd "$service" && mvn -q -B clean package -DskipTests)
done

echo "All services built. Now run: podman-compose -f podman-compose.yml up --build"
