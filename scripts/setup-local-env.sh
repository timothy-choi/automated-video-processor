#!/usr/bin/env bash
# Generate a local .env for docker compose. Development secrets only.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

FORCE=0
if [[ "${1:-}" == "--force" ]]; then
	FORCE=1
fi

if [[ -f .env && "$FORCE" -ne 1 ]]; then
	echo ".env already exists; not overwriting. Pass --force to regenerate."
	exit 0
fi

if [[ ! -f .env.example ]]; then
	echo "missing .env.example" >&2
	exit 1
fi

need() {
	if ! command -v "$1" >/dev/null 2>&1; then
		echo "required command not found: $1" >&2
		exit 1
	fi
}

need openssl
need python3

hex32() {
	openssl rand -hex 32
}

SCHEDULER_SERVICE_TOKEN="$(hex32)"
WORKER_TOKEN_PEPPER="$(hex32)"
BOOTSTRAP_SECRET="$(hex32)"
MEDIA_PLATFORM_BOOTSTRAP_API_KEY="mp_live_${BOOTSTRAP_SECRET}"

WORKER_A_TOKEN="$(python3 scripts/mint-worker-token.py "$WORKER_TOKEN_PEPPER" worker-a)"
WORKER_B_TOKEN="$(python3 scripts/mint-worker-token.py "$WORKER_TOKEN_PEPPER" worker-b)"

MINIO_API_PORT="${MINIO_API_PORT:-9000}"
INGRESS_STORAGE_HTTPS_PORT="${INGRESS_STORAGE_HTTPS_PORT:-9443}"
OBJECT_STORE_PUBLIC_ENDPOINT="${OBJECT_STORE_PUBLIC_ENDPOINT:-https://localhost:${INGRESS_STORAGE_HTTPS_PORT}}"

cat > .env <<EOF
POSTGRES_USER=media_platform
POSTGRES_PASSWORD=media_platform
RABBITMQ_USERNAME=media_platform
RABBITMQ_PASSWORD=media_platform
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin
MINIO_API_PORT=${MINIO_API_PORT}
INGRESS_STORAGE_HTTPS_PORT=${INGRESS_STORAGE_HTTPS_PORT}
OBJECT_STORE_PUBLIC_ENDPOINT=${OBJECT_STORE_PUBLIC_ENDPOINT}
OBJECT_STORE_REGION=us-east-1
INGRESS_HTTP_PORT=80
INGRESS_HTTPS_PORT=443
SCHEDULER_SERVICE_TOKEN=${SCHEDULER_SERVICE_TOKEN}
WORKER_TOKEN_PEPPER=${WORKER_TOKEN_PEPPER}
WORKER_A_TOKEN=${WORKER_A_TOKEN}
WORKER_B_TOKEN=${WORKER_B_TOKEN}
MEDIA_PLATFORM_BOOTSTRAP_API_KEY=${MEDIA_PLATFORM_BOOTSTRAP_API_KEY}
ACCOUNT_REGISTRATION_ENABLED=false
ARTIFACT_URL_TTL=15m
MEDIA_UPLOAD_MAX_BYTES=2147483648
MEDIA_UPLOAD_URL_TTL=15m
WORKER_PLACEMENT_POLICY=LEAST_LOADED
SCHEDULER_POLL_INTERVAL=500ms
EOF

chmod 600 .env
echo "wrote .env (mode 600). Local development secrets only — not for production."
echo "next: docker compose up --build"
echo "API:  curl -k https://localhost/health"
echo "Auth: source the MEDIA_PLATFORM_BOOTSTRAP_API_KEY from .env (do not commit it)"
