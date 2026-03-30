#!/usr/bin/env bash
# =============================================================================
# CS6650 Assignment 3 – PostgreSQL 15 setup script
#
# Run this once on the EC2 instance that hosts your PostgreSQL database.
# Usage:  bash setup.sh
# =============================================================================
set -euo pipefail

DB_NAME="chatflow"
DB_USER="chatuser"
DB_PASS="chatpassword"
SCHEMA_FILE="$(dirname "$0")/schema.sql"

echo "=== Installing PostgreSQL 15 ==="
sudo apt-get update -y
sudo apt-get install -y postgresql-15 postgresql-client-15

echo "=== Starting PostgreSQL service ==="
sudo systemctl enable postgresql
sudo systemctl start postgresql

echo "=== Creating database and user ==="
sudo -u postgres psql <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${DB_USER}') THEN
    CREATE USER ${DB_USER} WITH PASSWORD '${DB_PASS}';
  END IF;
END
\$\$;

SELECT 'CREATE DATABASE ${DB_NAME} OWNER ${DB_USER}'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${DB_NAME}')\gexec

GRANT ALL PRIVILEGES ON DATABASE ${DB_NAME} TO ${DB_USER};
SQL

# Connect to the new database and set schema privileges
sudo -u postgres psql -d "${DB_NAME}" <<SQL
GRANT ALL ON SCHEMA public TO ${DB_USER};
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO ${DB_USER};
SQL

echo "=== Applying schema ==="
PGPASSWORD="${DB_PASS}" psql \
    -h localhost -U "${DB_USER}" -d "${DB_NAME}" \
    -f "${SCHEMA_FILE}"

echo "=== Tuning postgresql.conf for high write throughput ==="
PG_CONF=$(sudo -u postgres psql -t -c "SHOW config_file;" | tr -d ' ')

sudo tee -a "${PG_CONF}" > /dev/null <<CONF

# --- CS6650 Assignment 3 tuning ---
# Increase shared_buffers to ~25% of RAM (adjust for your instance)
shared_buffers = 512MB
effective_cache_size = 1536MB

# WAL tuning for batch writes
wal_buffers = 64MB
checkpoint_completion_target = 0.9
max_wal_size = 2GB

# Connection settings
max_connections = 200

# Logging (useful for monitoring)
log_min_duration_statement = 500   # log queries slower than 500 ms
CONF

echo "=== Restarting PostgreSQL to apply config ==="
sudo systemctl restart postgresql

echo ""
echo "=== Setup complete ==="
echo "  Host:     localhost"
echo "  Port:     5432"
echo "  Database: ${DB_NAME}"
echo "  User:     ${DB_USER}"
echo "  Password: ${DB_PASS}"
echo ""
echo "Connection string:"
echo "  jdbc:postgresql://localhost:5432/${DB_NAME}"
