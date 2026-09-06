# TODO — gateway-side

## Seguridad

- **SSH tunnel: verificación de host key del servidor**
  Actualmente `StrictHostKeyChecking=no` (workaround temporal). La solución correcta es que el
  backend incluya el host key del servidor en el payload del comando `create-tunnel`. El gateway
  lo escribe en un known_hosts temporal (`/tmp/tunnel_known_hosts`) y pasa
  `-o UserKnownHostsFile=/tmp/tunnel_known_hosts -o StrictHostKeyChecking=yes` a SSH.
  Así funciona sin preconfigurar nada en el dispositivo y previene MITM.
  Ver `PlatformService.createReverseTunnel()`.

## Empaquetado

- **OTA deshabilitado en modo contenedor** — revisar más adelante.
  `OtaService` devuelve `{"status":"not_supported"}` cuando `gateway.deployment.mode=container`
  (seteado por el `Dockerfile`) porque `sudo systemctl restart` no tiene systemd a quién hablarle
  dentro de un contenedor. Hoy las actualizaciones en addon/Docker dependen del store de addons de
  HA o de `docker pull` + recreate. Si en algún momento se quiere un OTA real en contenedor, la
  opción es escribir el JAR en un volumen persistente y hacer `exit(0)` confiando en la política de
  restart de Docker/Supervisor para relanzar el proceso — no implementado a propósito por ahora.
  Ver `OTA.md` → "Container mode".
