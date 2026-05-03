#!/bin/sh
# Entrypoint for the bundled-Postgres deployment mode (STEP9).
#
# On first run, initializes a private PostgreSQL cluster at $PGDATA with
# loopback-only access. On every run, starts the cluster as a background
# process and runs the Spring Boot JAR in the foreground. SIGTERM/SIGINT
# is forwarded to the JVM, after which Postgres is shut down with -m fast.

PGDATA="${PGDATA:-/var/lib/postgresql/data}"
DB_USER=calendar
DB_NAME=calendar

set -e

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  echo "[entrypoint] initializing PostgreSQL cluster at $PGDATA"
  initdb -D "$PGDATA" \
    --username="$DB_USER" \
    --auth-local=trust \
    --auth-host=trust \
    --encoding=UTF8 \
    --locale=C >/dev/null

  cat > "$PGDATA/pg_hba.conf" <<'EOF'
local all all trust
host  all all 127.0.0.1/32 trust
host  all all ::1/128      trust
EOF

  cat > "$PGDATA/postgresql.conf" <<'EOF'
listen_addresses = '127.0.0.1'
unix_socket_directories = '/tmp'
EOF

  pg_ctl -D "$PGDATA" -o "-k /tmp" -w start >/dev/null
  createdb -h /tmp -U "$DB_USER" "$DB_NAME"
  pg_ctl -D "$PGDATA" -m fast -w stop >/dev/null
fi

set +e

postgres -D "$PGDATA" -k /tmp &
PG_PID=$!

shutdown() {
  if [ -n "${JVM_PID:-}" ]; then
    kill -TERM "$JVM_PID" 2>/dev/null
    wait "$JVM_PID" 2>/dev/null
  fi
  if kill -0 "$PG_PID" 2>/dev/null; then
    pg_ctl -D "$PGDATA" -m fast -w stop 2>/dev/null
  fi
}
trap shutdown TERM INT EXIT

echo "[entrypoint] waiting for Postgres"
until pg_isready -h 127.0.0.1 -U "$DB_USER" -d "$DB_NAME" -q; do
  sleep 0.5
done
echo "[entrypoint] Postgres ready; starting JVM"

java -jar /app/app.jar &
JVM_PID=$!
wait "$JVM_PID"
