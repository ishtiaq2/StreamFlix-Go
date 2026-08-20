BASE=../streamflix-stack

cat > "$BASE/catalog-api/requirements.txt" << 'EOF'
fastapi==0.115.0
uvicorn==0.30.6
psycopg2-binary==2.9.9
requests==2.32.3
EOF

cat > "$BASE/catalog-api/main.py" << 'EOF'
import os
import requests
import psycopg2
from fastapi import FastAPI, HTTPException

app = FastAPI(title="StreamFlix Catalog API")

DB_DSN = os.environ.get(
    "DATABASE_URL",
    "dbname=streamflix user=streamflix password=streamflixpass host=database"
)
CORE_ENGINE_URL = os.environ.get("CORE_ENGINE_URL", "http://core-engine:8981")


def db_connection():
    return psycopg2.connect(DB_DSN)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/titles")
def list_titles():
    with db_connection() as conn, conn.cursor() as cur:
        cur.execute("SELECT id, name, genre FROM titles ORDER BY id")
        rows = cur.fetchall()
    return [{"id": r[0], "name": r[1], "genre": r[2]} for r in rows]


@app.post("/play/{title_id}")
def play_title(title_id: int, user_id: int = 1):
    with db_connection() as conn, conn.cursor() as cur:
        cur.execute("SELECT name FROM titles WHERE id = %s", (title_id,))
        row = cur.fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Title not found")

        cur.execute(
            "INSERT INTO watch_history (user_id, title_id) VALUES (%s, %s)",
            (user_id, title_id),
        )
        conn.commit()

    stream_id = f"stream-{user_id}-{title_id}"

    try:
        requests.post(
            f"{CORE_ENGINE_URL}/events",
            json={
                "uei": "streamflix/playback/started",
                "streamId": stream_id,
                "message": f"Playback started for '{row[0]}'",
            },
            timeout=2,
        )
    except requests.RequestException:
        pass  # a monitoring hiccup shouldn't block playback itself

    return {"streamId": stream_id, "title": row[0], "status": "playing"}
EOF

cat > "$BASE/catalog-api/Dockerfile" << 'EOF'
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py .
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
EOF

cat > "$BASE/web-ui/index.html" << 'EOF'
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>StreamFlix</title>
  <style>
    body { font-family: sans-serif; background: #141414; color: #fff; padding: 2rem; }
    .title-card { display: inline-block; background: #222; padding: 1rem; margin: 0.5rem;
                  border-radius: 8px; cursor: pointer; width: 180px; }
    .title-card:hover { background: #333; }
    #status { margin-top: 1rem; color: #46d369; }
  </style>
</head>
<body>
  <h1>StreamFlix</h1>
  <div id="titles">Loading catalog...</div>
  <div id="status"></div>

  <script>
    const API_BASE = "/api";

    fetch(`${API_BASE}/titles`)
      .then(res => res.json())
      .then(titles => {
        document.getElementById("titles").innerHTML = titles.map(t => `
          <div class="title-card" onclick="play(${t.id}, '${t.name}')">
            <strong>${t.name}</strong><br><small>${t.genre}</small>
          </div>
        `).join("");
      });

    function play(id, name) {
      fetch(`${API_BASE}/play/${id}`, { method: "POST" })
        .then(res => res.json())
        .then(data => {
          document.getElementById("status").innerText =
            `Now playing: ${data.title} (stream ${data.streamId})`;
        });
    }
  </script>
</body>
</html>
EOF

cat > "$BASE/web-ui/Dockerfile" << 'EOF'
FROM docker.io/library/nginx:alpine
COPY index.html /usr/share/nginx/html/index.html
EXPOSE 80
EOF

cat > "$BASE/edge-lb/nginx.conf" << 'EOF'
events {}

http {
    upstream catalog_backend {
        server catalog-api:8000;
    }

    server {
        listen 8080;

        location /api/ {
            proxy_pass http://catalog_backend/;
            proxy_set_header Host $host;
        }

        location / {
            proxy_pass http://web-ui:80/;
        }
    }
}
EOF

cat > "$BASE/edge-lb/Dockerfile" << 'EOF'
FROM docker.io/library/nginx:alpine
COPY nginx.conf /etc/nginx/nginx.conf
EXPOSE 8080
EOF

echo "catalog-api, web-ui, edge-lb done"