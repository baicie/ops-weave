"""Development smoke only: boot Java platform/worker against explicit local PG + VM test storage."""
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time
from urllib.error import URLError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parents[1]
JDBC = os.environ["OPSWEAVE_TEST_JDBC_URL"]
VM = os.environ["OPSWEAVE_TEST_VM_URL"].rstrip("/")
USER = os.environ["OPSWEAVE_TEST_JDBC_USER"]
for target in (JDBC.removeprefix("jdbc:"), VM):
    parsed = urlparse(target)
    if parsed.hostname not in ("127.0.0.1", "::1") or parsed.username or parsed.password:
        raise SystemExit("This development check requires explicit loopback test storage without URL credentials")
JAVA = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
START = 1789992001  # The fixture's second second: one unambiguous millisecond point with value 0.40.
STREAM = "boot-smoke-" + uuid.uuid4().hex[:12]
TOKEN = secrets.token_urlsafe(32)


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def request(url, method="GET", token=None):
    headers = {} if token is None else {"Authorization": "Bearer " + token}
    with urlopen(Request(url, method=method, headers=headers), timeout=3) as response:
        return response.read()


def jar(app):
    candidates = [path for path in (ROOT / "apps" / app / "build/libs").glob("*.jar") if not path.name.endswith("-plain.jar")]
    if len(candidates) != 1:
        raise SystemExit("Build the two bootJar tasks before running the stack smoke")
    return str(candidates[0])


def spawn(app, app_port, overrides):
    env = os.environ.copy()
    env.update(overrides)
    return subprocess.Popen([JAVA, "-jar", jar(app), "--server.address=127.0.0.1", f"--server.port={app_port}"],
                            cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


platform = worker = None
try:
    platform_port = port()
    platform_url = f"http://127.0.0.1:{platform_port}"
    platform = spawn("platform-api", platform_port, {
        "OPSWEAVE_AUTH_MODE": "dev", "OPSWEAVE_DEV_TOKEN": TOKEN, "OPSWEAVE_DEV_TENANT": "tenant-demo",
        "OPSWEAVE_DEV_PERMISSIONS": "entity.read,metric.read,source.sync", "OPSWEAVE_DEV_ENTITY_IDS": "",
        "OPSWEAVE_INVENTORY_STORE": "memory", "OPSWEAVE_ZABBIX_MODE": "fixture", "OPSWEAVE_ZABBIX_SOURCE": "zabbix-1",
    })
    for _ in range(60):
        if platform.poll() is not None:
            raise SystemExit("Fixture platform exited before readiness")
        try:
            request(platform_url + "/actuator/health")
            break
        except (URLError, TimeoutError):
            time.sleep(1)
    else:
        raise SystemExit("Fixture platform readiness timed out")
    synced = json.loads(request(platform_url + "/api/v1/integrations/zabbix/items/sync", "POST", TOKEN))
    assert synced["dataMode"] == "labeled-fixture" and synced["accepted"] == 1
    worker = spawn("ingestion-worker", port(), {
        "OPSWEAVE_HISTORY_ENABLED": "true", "OPSWEAVE_HISTORY_PLATFORM_URL": platform_url,
        "OPSWEAVE_HISTORY_PLATFORM_TOKEN": TOKEN, "OPSWEAVE_HISTORY_DATA_MODE": "labeled-fixture",
        "OPSWEAVE_HISTORY_SOURCE": "zabbix-1", "OPSWEAVE_HISTORY_ITEM_ID": "20001", "OPSWEAVE_HISTORY_STREAM": STREAM,
        "OPSWEAVE_HISTORY_INITIAL_FROM": str(START), "OPSWEAVE_HISTORY_POLL_MILLIS": "1000",
        "OPSWEAVE_HISTORY_VICTORIA_URL": VM, "OPSWEAVE_HISTORY_JDBC_URL": JDBC, "OPSWEAVE_HISTORY_JDBC_USER": USER,
        "OPSWEAVE_HISTORY_JDBC_PASSWORD": os.environ.get("OPSWEAVE_TEST_JDBC_PASSWORD", ""),
    })
    pg_env = os.environ.copy()
    pg_env["PGPASSWORD"] = os.environ.get("OPSWEAVE_TEST_JDBC_PASSWORD", "")
    for _ in range(60):
        if worker.poll() is not None:
            raise SystemExit("History worker exited before checkpoint confirmation")
        result = subprocess.run(["psql", JDBC.removeprefix("jdbc:"), "-U", USER, "-At", "-c",
                                 f"SELECT completed_through FROM ingestion.history_checkpoint WHERE stream_name='{STREAM}'"],
                                env=pg_env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=5)
        if result.returncode == 0 and result.stdout.strip() and int(result.stdout.strip()) >= START:
            break
        time.sleep(1)
    else:
        raise SystemExit("Worker checkpoint confirmation timed out")
    query = urlencode({"match[]": 'opsweave_metric_value{tenant_id="tenant-demo",source_instance_id="zabbix-1",external_item_id="20001",data_mode="labeled-fixture"}',
                       "start": START, "end": START, "reduce_mem_usage": "1"})
    rows = [json.loads(line) for line in request(VM + "/api/v1/export?" + query).decode().splitlines() if line]
    assert any(timestamp == START * 1000 and value == 0.4 for row in rows for timestamp, value in zip(row["timestamps"], row["values"]))
    print("Java platform -> scheduled Java worker -> real VictoriaMetrics + PostgreSQL: PASS (labeled fixture source)")
finally:
    for process in (worker, platform):
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
