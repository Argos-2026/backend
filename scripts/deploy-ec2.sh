#!/usr/bin/env bash
set -Eeuo pipefail
trap 'code=$?; echo "[deploy] FAILED at line $LINENO (exit $code): $BASH_COMMAND" >&2' ERR
echo "[deploy] starting deployment"

IMAGE="${1:-}"
if [[ ! "$IMAGE" =~ ^ghcr\.io/argos-2026/backend:[0-9a-f]{40}$ ]]; then
  echo "Invalid image reference" >&2
  exit 1
fi

cd /home/ec2-user/apps/backend
if [[ ! -f compose.yaml || ! -f Caddyfile ]]; then
  echo "Expected existing compose.yaml and Caddyfile in /home/ec2-user/apps/backend" >&2
  exit 1
fi

# Capture Compose output fully before checking it: grep -q in a pipeline
# can close stdout early and fail under pipefail.
echo "[deploy] checking available Compose services"
services="$(docker compose config --services)"
for required in springboot caddy; do
  if ! grep -Fxq "$required" <<< "$services"; then
    echo "[deploy] missing service: $required" >&2
    exit 1
  fi
done

cat > compose.deploy.yaml <<'YAML'
services:
  springboot:
    image: ${DEPLOY_IMAGE:?DEPLOY_IMAGE_required}
YAML

# If GHCR package is private, ec2-user must already be logged in to ghcr.io.
echo "[deploy] pulling Docker image"
docker pull "$IMAGE"
export DEPLOY_IMAGE="$IMAGE"
echo "[deploy] validating Compose override"
docker compose -f compose.yaml -f compose.deploy.yaml config --quiet
echo "[deploy] updating springboot (not caddy)"
docker compose -f compose.yaml -f compose.deploy.yaml \
  up -d --no-deps --no-build --pull never springboot

# 2xx/3xx/4xx prove an HTTP server is listening; / may legitimately be 404.
for attempt in {1..20}; do
  status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 3 \
    http://127.0.0.1:8080/ 2>/dev/null || true)"
  if [[ "$status" =~ ^[234][0-9][0-9]$ ]]; then
    printf 'DEPLOY_IMAGE=%s\n' "$IMAGE" > .deploy.env
    chmod 600 .deploy.env
    echo "Backend deployed: $IMAGE (HTTP $status)"
    docker compose -f compose.yaml -f compose.deploy.yaml ps springboot
    exit 0
  fi
  sleep 3
done

echo "Backend failed HTTP readiness check" >&2
docker compose -f compose.yaml -f compose.deploy.yaml logs --tail=100 springboot >&2
exit 1
