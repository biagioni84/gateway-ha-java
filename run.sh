#!/bin/sh
set -e

# HA Supervisor mounts the addon's configured options as JSON here. Translate the ones we expose
# in config.yaml into env vars that Spring resolves via its standard relaxed env-var binding
# (GATEWAY_AUTH_USERNAME -> gateway.auth.username, etc). In standalone-docker mode this file
# doesn't exist — env vars are already set directly via `docker run -e` / compose, nothing to do.
OPTIONS=/data/options.json
if [ -f "$OPTIONS" ]; then
  auth_username=$(jq -r '.auth_username // empty' "$OPTIONS")
  auth_password=$(jq -r '.auth_password // empty' "$OPTIONS")
  log_level=$(jq -r '.log_level // empty' "$OPTIONS")

  [ -n "$auth_username" ] && export GATEWAY_AUTH_USERNAME="$auth_username"
  [ -n "$auth_password" ] && export GATEWAY_AUTH_PASSWORD="$auth_password"
  [ -n "$log_level" ] && export LOGGING_LEVEL_UY_PLOMO_GATEWAY="$log_level"
fi

exec java -jar /app/gateway.jar
