#!/usr/bin/env bash
# servers.sh SET: starts one set of the SQL servers as TestServer does, prints when each is ready.
set -u
set_=$1; root=${ROOT:-.}; s=$root/core/src/test/resources/sample-schema; pw='Pw-hoori-7qX!3z'
t0=$(date +%s.%N)
docker run -d --name pg$set_ -e POSTGRES_USER=hoori -e POSTGRES_PASSWORD="$pw" -e POSTGRES_DB=hoori \
  --tmpfs /var/lib/postgresql -p 5432 -v "$s/postgresql.sql:/docker-entrypoint-initdb.d/schema.sql:ro" postgres:18 >/dev/null
docker run -d --name maria$set_ -e MARIADB_USER=hoori -e MARIADB_PASSWORD="$pw" -e MARIADB_DATABASE=hoori \
  -e MARIADB_ROOT_PASSWORD="root-$pw" --tmpfs /var/lib/mysql -p 3306 \
  -v "$s/mariadb.sql:/docker-entrypoint-initdb.d/schema.sql:ro" mariadb:12 >/dev/null
docker run -d --name mysql$set_ -e MYSQL_USER=hoori -e MYSQL_PASSWORD="$pw" -e MYSQL_DATABASE=hoori \
  -e MYSQL_ROOT_PASSWORD="root-$pw" --tmpfs /var/lib/mysql -p 3306 \
  -v "$s/mysql.sql:/docker-entrypoint-initdb.d/schema.sql:ro" mysql:9 >/dev/null
wait_for() { # name regex count
  until [ "$(docker logs "$1" 2>&1 | grep -cE "$2")" -ge "$3" ]; do sleep 0.1; done
  echo "set $set_ $1 ready $(echo "$(date +%s.%N) $t0" | awk '{printf "%.1f", $1-$2}') s"
}
wait_for pg$set_ 'database system is ready to accept connections' 2 &
wait_for maria$set_ 'port: 3306\b' 1 &
wait_for mysql$set_ 'port: 3306\b' 1 &
wait
