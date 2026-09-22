#!/usr/bin/env bash
# Load images built in CI and start the loopback stack on the selected host.
# Required env: SSHPASS, OPSWEAVE_DEPLOY_HOST, OPSWEAVE_DEPLOY_PORT, OPSWEAVE_DEPLOY_USER,
# OPSWEAVE_DEPLOY_PATH, OPSWEAVE_DEPLOY_SSH_HOSTKEY, OPSWEAVE_DEPLOY_POSTGRES_PASSWORD, OPSWEAVE_IMAGE_TAG.
set -euo pipefail

: "${SSHPASS:?SSHPASS is required}"
: "${OPSWEAVE_DEPLOY_HOST:?OPSWEAVE_DEPLOY_HOST is required}"
: "${OPSWEAVE_DEPLOY_PORT:?OPSWEAVE_DEPLOY_PORT is required}"
: "${OPSWEAVE_DEPLOY_USER:?OPSWEAVE_DEPLOY_USER is required}"
: "${OPSWEAVE_DEPLOY_PATH:?OPSWEAVE_DEPLOY_PATH is required}"
: "${OPSWEAVE_DEPLOY_SSH_HOSTKEY:?OPSWEAVE_DEPLOY_SSH_HOSTKEY is required}"
: "${OPSWEAVE_DEPLOY_POSTGRES_PASSWORD:?OPSWEAVE_DEPLOY_POSTGRES_PASSWORD is required}"
: "${OPSWEAVE_IMAGE_TAG:?OPSWEAVE_IMAGE_TAG is required}"

known="$(mktemp)"
envfile="$(mktemp)"
chmod 600 "$known" "$envfile"
trap 'rm -f "$known" "$envfile"' EXIT
printf '%s\n' "$OPSWEAVE_DEPLOY_SSH_HOSTKEY" > "$known"

ssh_opts=(
  -o "UserKnownHostsFile=${known}"
  -o StrictHostKeyChecking=yes
  -o PreferredAuthentications=password
  -o PubkeyAuthentication=no
  -o ConnectTimeout=20
)
target="${OPSWEAVE_DEPLOY_USER}@${OPSWEAVE_DEPLOY_HOST}"

remote() {
  sshpass -e ssh "${ssh_opts[@]}" -p "$OPSWEAVE_DEPLOY_PORT" "$target" "$@"
}

copy() {
  sshpass -e scp "${ssh_opts[@]}" -P "$OPSWEAVE_DEPLOY_PORT" "$1" "${target}:$2"
}

remote "mkdir -p $(printf '%q' "$OPSWEAVE_DEPLOY_PATH")"
copy deploy/compose/remote.yaml "${OPSWEAVE_DEPLOY_PATH}/compose.yaml"

umask 077
cat > "$envfile" <<EOF
POSTGRES_DB=opsweave
POSTGRES_USER=opsweave_dev
POSTGRES_PASSWORD=${OPSWEAVE_DEPLOY_POSTGRES_PASSWORD}
OPSWEAVE_IMAGE_TAG=${OPSWEAVE_IMAGE_TAG}
EOF
copy "$envfile" "${OPSWEAVE_DEPLOY_PATH}/.env"
remote "chmod 600 $(printf '%q' "${OPSWEAVE_DEPLOY_PATH}/.env")"

echo "Streaming images to ${OPSWEAVE_DEPLOY_HOST}"
docker save \
  "opsweave-platform-api:${OPSWEAVE_IMAGE_TAG}" \
  "opsweave-ingestion-worker:${OPSWEAVE_IMAGE_TAG}" \
  "opsweave-agent-runtime:${OPSWEAVE_IMAGE_TAG}" \
  "opsweave-web-console:${OPSWEAVE_IMAGE_TAG}" \
  | gzip -1 \
  | remote "gunzip | docker load"

remote "cd $(printf '%q' "$OPSWEAVE_DEPLOY_PATH") && docker compose --env-file .env -f compose.yaml up -d --remove-orphans --no-build"

remote "bash -s" <<EOS
set -euo pipefail
cd $(printf '%q' "$OPSWEAVE_DEPLOY_PATH")
wait_url() {
  url="\$1"
  i=1
  while [ "\$i" -le 36 ]; do
    if curl -fsS --max-time 5 "\$url" >/dev/null; then
      echo "ok \$url"
      return 0
    fi
    i=\$((i + 1))
    sleep 5
  done
  echo "health check failed: \$url" >&2
  docker compose --env-file .env -f compose.yaml ps >&2 || true
  return 1
}
wait_url http://127.0.0.1:18080/actuator/health
wait_url http://127.0.0.1:18081/actuator/health
wait_url http://127.0.0.1:18090/healthz
wait_url http://127.0.0.1:18088/
EOS
