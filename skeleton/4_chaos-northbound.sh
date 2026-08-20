BASE=../streamflix-stack

cat > "$BASE/chaos-agent/requirements.txt" << 'EOF'
requests==2.32.3
paramiko==3.4.0
EOF

cat > "$BASE/chaos-agent/chaos.py" << 'EOF'
"""
A small Chaos-Monkey-style agent: checks catalog-api's health over HTTP,
and (optionally) uses SSH to kill a process inside a target container to
simulate a real outage -- then reports the resulting event to core-engine,
the same way any monitoring integration would.
"""
import sys

import requests
import paramiko

CORE_ENGINE_URL = "http://core-engine:8981"
CATALOG_HEALTH_URL = "http://catalog-api:8000/health"


def check_health() -> bool:
    try:
        resp = requests.get(CATALOG_HEALTH_URL, timeout=2)
        return resp.status_code == 200
    except requests.RequestException:
        return False


def report_event(uei: str, stream_id: str, message: str) -> None:
    requests.post(
        f"{CORE_ENGINE_URL}/events",
        json={"uei": uei, "streamId": stream_id, "message": message},
        timeout=2,
    )
    print(f"Reported: {uei} ({message})")


def simulate_outage_via_ssh(host: str, port: int, user: str, password: str) -> None:
    """
    Connects over SSH and kills the target process -- a real chaos-engineering
    action, not just an HTTP health check. Requires an SSH server running in
    the target container (off by default -- see the guide for why).
    """
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(host, port=port, username=user, password=password, timeout=5)
    client.exec_command("pkill -f uvicorn")
    client.close()


def run_chaos_check(stream_id: str = "stream-chaos-test") -> None:
    healthy = check_health()
    if healthy:
        report_event(
            "streamflix/chaos/healthcheckPassed", stream_id,
            "catalog-api responded normally"
        )
    else:
        report_event(
            "streamflix/chaos/healthcheckFailed", stream_id,
            "catalog-api did not respond to health check"
        )


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "check"

    if mode == "check":
        run_chaos_check()
    elif mode == "break":
        simulate_outage_via_ssh(
            host="catalog-api", port=22, user="root", password="chaospass"
        )
        report_event(
            "streamflix/chaos/inducedFailure", "stream-chaos-test",
            "chaos-agent deliberately killed catalog-api"
        )
    else:
        print("usage: python chaos.py [check|break]")
EOF

cat > "$BASE/chaos-agent/Dockerfile" << 'EOF'
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY chaos.py .
CMD ["sleep", "infinity"]
EOF

cat > "$BASE/northbound/requirements.txt" << 'EOF'
fastapi==0.115.0
uvicorn==0.30.6
psycopg2-binary==2.9.9
requests==2.32.3
EOF

cat > "$BASE/northbound/main.py" << 'EOF'
import os
import requests
import psycopg2
from fastapi import FastAPI

app = FastAPI(title="StreamFlix Northbound Interface")

DB_DSN = os.environ.get(
    "DATABASE_URL",
    "dbname=streamflix user=streamflix password=streamflixpass host=database"
)
CORE_ENGINE_URL = os.environ.get("CORE_ENGINE_URL", "http://core-engine:8981")


@app.get("/status")
def status():
    alarms = requests.get(f"{CORE_ENGINE_URL}/alarms", timeout=3).json()
    return {
        "service": "StreamFlix",
        "active_alarms": len(alarms),
        "alarms": alarms,
    }


@app.get("/metrics")
def metrics():
    alarms = requests.get(f"{CORE_ENGINE_URL}/alarms", timeout=3).json()

    with psycopg2.connect(DB_DSN) as conn, conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM watch_history")
        total_plays = cur.fetchone()[0]

    lines = [
        f"streamflix_active_alarms {len(alarms)}",
        f"streamflix_total_plays {total_plays}",
    ]
    return "\n".join(lines)
EOF

cat > "$BASE/northbound/Dockerfile" << 'EOF'
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py .
EXPOSE 9000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "9000"]
EOF

echo "chaos-agent, northbound done"