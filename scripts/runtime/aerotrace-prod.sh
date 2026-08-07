#!/usr/bin/env bash

set -Eeuo pipefail

ACTION="${1:-status}"

REPOSITORY_ROOT="$(
    cd "$(dirname "${BASH_SOURCE[0]}")/../.."
    pwd
)"

BASE_COMPOSE_FILE="${REPOSITORY_ROOT}/docker-compose.yaml"
APP_COMPOSE_FILE="${REPOSITORY_ROOT}/docker-compose.app.yaml"
PROD_COMPOSE_FILE="${REPOSITORY_ROOT}/docker-compose.prod.yaml"
EDGE_COMPOSE_FILE="${REPOSITORY_ROOT}/docker-compose.edge.yaml"

EDGE_NETWORK_NAME="edge-gateway-net"

REQUIRED_SECRET_FILES=(
    ".env"
    "otel-collector.env"
    "frontend.env"
)

HEALTH_CONTAINERS=(
    "aerotrace-timescaledb"
    "aerotrace-backend"
    "aerotrace-frontend"
)

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

compose() {
    docker compose \
        -f "${BASE_COMPOSE_FILE}" \
        -f "${APP_COMPOSE_FILE}" \
        -f "${PROD_COMPOSE_FILE}" \
        -f "${EDGE_COMPOSE_FILE}" \
        --profile app \
        "$@"
}

assert_required_commands() {
    command -v docker >/dev/null 2>&1 ||
        fail "docker was not found."

    docker compose version >/dev/null 2>&1 ||
        fail "docker compose was not found."
}

assert_required_files() {
    local relative_path
    local full_path

    for relative_path in "${REQUIRED_SECRET_FILES[@]}"; do
        full_path="${REPOSITORY_ROOT}/${relative_path}"

        if [[ ! -f "${full_path}" ]]; then
            fail "Required local environment file was not found: ${full_path}"
        fi
    done
}

assert_external_network() {
    if ! docker network inspect \
        "${EDGE_NETWORK_NAME}" \
        >/dev/null 2>&1; then

        fail \
            "Required external Docker network was not found: ${EDGE_NETWORK_NAME}"
    fi

    echo "External Docker network is available: ${EDGE_NETWORK_NAME}"
}

wait_for_healthy_container() {
    local container_name="$1"
    local timeout_seconds="${2:-120}"
    local started_at
    local elapsed
    local status

    started_at="$(date +%s)"

    while true; do
        status="$(
            docker inspect \
                "${container_name}" \
                --format \
                '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
                2>/dev/null ||
                true
        )"

        case "${status}" in
            healthy)
                echo "${container_name} is healthy."
                return 0
                ;;

            exited | dead)
                fail "${container_name} entered state: ${status}"
                ;;
        esac

        elapsed="$(( $(date +%s) - started_at ))"

        if (( elapsed >= timeout_seconds )); then
            fail "Timed out waiting for ${container_name} to become healthy."
        fi

        sleep 2
    done
}

assert_collector_running() {
    local status

    status="$(
        docker inspect \
            aerotrace-otel-collector \
            --format '{{.State.Status}}'
    )"

    if [[ "${status}" != "running" ]]; then
        fail "Collector is not running. Current state: ${status}"
    fi

    echo "aerotrace-otel-collector is running."
}

assert_storage_init_succeeded() {
    local status
    local exit_code

    status="$(
        docker inspect \
            aerotrace-otel-storage-init \
            --format '{{.State.Status}}'
    )"

    exit_code="$(
        docker inspect \
            aerotrace-otel-storage-init \
            --format '{{.State.ExitCode}}'
    )"

    if [[ "${status}" != "exited" || "${exit_code}" != "0" ]]; then
        fail \
            "Storage init did not complete successfully. " \
            "status=${status}, exitCode=${exit_code}"
    fi

    echo "aerotrace-otel-storage-init completed successfully."
}

get_published_ports() {
    local container_name="$1"

    docker port "${container_name}" 2>/dev/null || true
}

assert_no_published_ports() {
    local container_name="$1"
    local published_ports

    published_ports="$(
        get_published_ports "${container_name}"
    )"

    if [[ -n "${published_ports}" ]]; then
        fail \
            "${container_name} must not publish host ports:" \
            "${published_ports}"
    fi

    echo "${container_name} publishes no host ports."
}

assert_loopback_binding() {
    local container_name="$1"
    local container_port="$2"
    local expected_host_port="$3"
    local actual_binding
    local expected_binding

    actual_binding="$(
        docker port \
            "${container_name}" \
            "${container_port}/tcp" \
            2>/dev/null ||
            true
    )"

    expected_binding="127.0.0.1:${expected_host_port}"

    if [[ "${actual_binding}" != "${expected_binding}" ]]; then
        fail \
            "Unexpected binding for ${container_name} " \
            "${container_port}/tcp. " \
            "expected=${expected_binding}, actual=${actual_binding:-<none>}"
    fi

    echo \
        "${container_name} ${container_port}/tcp" \
        "is bound to ${actual_binding}."
}

assert_production_ports() {
    echo
    echo "Validating production port bindings."

    assert_no_published_ports \
        aerotrace-timescaledb

    assert_no_published_ports \
        aerotrace-backend

    assert_no_published_ports \
        aerotrace-frontend

    assert_loopback_binding \
        aerotrace-otel-collector \
        4317 \
        4317

    assert_loopback_binding \
        aerotrace-otel-collector \
        4318 \
        4318

    assert_loopback_binding \
        aerotrace-otel-collector \
        8888 \
        8888

    echo
    echo "Production port bindings are valid."
}

start_production_runtime() {
    assert_required_commands
    assert_required_files
    assert_external_network

    cd "${REPOSITORY_ROOT}"

    echo "Validating production Compose configuration."

    compose config --quiet

    echo "Building and starting AeroTrace production runtime."

    compose up \
        -d \
        --build \
        --remove-orphans

    local container_name

    for container_name in "${HEALTH_CONTAINERS[@]}"; do
        wait_for_healthy_container \
            "${container_name}"
    done

    assert_storage_init_succeeded
    assert_collector_running
    assert_production_ports

    echo
    echo "AeroTrace production runtime is ready."
    echo "Dashboard upstream: aerotrace-web:3000"
    echo "OTLP/gRPC:          127.0.0.1:4317"
    echo "OTLP/HTTP:          http://127.0.0.1:4318"
    echo "Collector metrics:  http://127.0.0.1:8888/metrics"
    echo

    compose ps -a
}

stop_production_runtime() {
    assert_required_commands

    cd "${REPOSITORY_ROOT}"

    echo "Removing production containers without deleting named volumes."

    compose down \
        --remove-orphans

    echo
    echo "Named volumes were preserved."
}

show_usage() {
    cat <<'EOF'
Usage:
  ./scripts/runtime/aerotrace-prod.sh up
  ./scripts/runtime/aerotrace-prod.sh down
  ./scripts/runtime/aerotrace-prod.sh restart
  ./scripts/runtime/aerotrace-prod.sh status
  ./scripts/runtime/aerotrace-prod.sh logs
  ./scripts/runtime/aerotrace-prod.sh config
  ./scripts/runtime/aerotrace-prod.sh ports
EOF
}

assert_required_commands

cd "${REPOSITORY_ROOT}"

case "${ACTION}" in
    up)
        start_production_runtime
        ;;

    down)
        stop_production_runtime
        ;;

    restart)
        stop_production_runtime
        start_production_runtime
        ;;

    status)
        compose ps -a
        ;;

    logs)
        compose logs \
            --tail 200 \
            --follow
        ;;

    config)
        assert_required_files
        compose config --quiet
        echo "Production Compose configuration is valid."
        ;;

    ports)
        assert_production_ports
        ;;

    help | --help | -h)
        show_usage
        ;;

    *)
        show_usage
        fail "Unsupported action: ${ACTION}"
        ;;
esac