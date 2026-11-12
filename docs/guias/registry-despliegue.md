# Registry, despliegue y rollback

El Registry (`registry:2.8.3`) arranca con `docker compose up`, publica `localhost:5001` y guarda
las imágenes en el volumen `registry_data`.

```bash
curl http://localhost:5001/v2/_catalog                    # repositorios
curl http://localhost:5001/v2/auth-service/tags/list       # tags de un servicio
```

Cada pipeline publica `localhost:5001/<servicio>:<commit>-<build>` (inmutable) y `:latest`, y archiva
el digest. La imagen lleva la etiqueta `org.opencontainers.image.revision` con el commit completo.

## Desplegar y revertir

```bash
export COMPOSE_PROJECT=microservicios          # proyecto Compose del entorno
bash scripts/deploy.sh auth-service <tag>       # despliega ese tag con smoke test
bash scripts/deploy.sh historial auth-service   # fecha, tag y digest de cada despliegue
bash scripts/deploy.sh rollback auth-service    # vuelve al tag anterior
docker inspect -f '{{.Config.Image}} {{index .Config.Labels "org.opencontainers.image.revision"}}' \
  "$(docker compose ps -q auth-service)"        # commit → imagen → contenedor
```

`deploy.sh` usa `docker-compose.deploy.yml`, que cambia el `build` del servicio por la imagen del
Registry, recrea solo ese servicio y espera a que su healthcheck quede `healthy` y el Gateway
responda. Si no, redespliega automáticamente el tag anterior. El historial vive en `.deploy/`.
La etapa «Deploy» de cada pipeline hace exactamente esto tras pasar la E2E.
