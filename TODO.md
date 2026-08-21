# TODO — gateway-side

## Seguridad

- **SSH tunnel: verificación de host key del servidor**
  Actualmente `StrictHostKeyChecking=no` (workaround temporal). La solución correcta es que el
  backend incluya el host key del servidor en el payload del comando `create-tunnel`. El gateway
  lo escribe en un known_hosts temporal (`/tmp/tunnel_known_hosts`) y pasa
  `-o UserKnownHostsFile=/tmp/tunnel_known_hosts -o StrictHostKeyChecking=yes` a SSH.
  Así funciona sin preconfigurar nada en el dispositivo y previene MITM.
  Ver `PlatformService.createReverseTunnel()`.
