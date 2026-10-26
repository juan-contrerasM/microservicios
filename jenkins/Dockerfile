FROM jenkins/jenkins:lts-jdk21

USER root

# Cliente Docker del PDF (docker.io) y Compose v2, que es el que invocan los Jenkinsfiles.
RUN apt-get update && \
    apt-get install -y --no-install-recommends docker.io ca-certificates curl && \
    mkdir -p /usr/libexec/docker/cli-plugins /usr/lib/docker/cli-plugins && \
    curl -fsSL "https://github.com/docker/compose/releases/download/v2.32.4/docker-compose-linux-x86_64" \
      -o /usr/libexec/docker/cli-plugins/docker-compose && \
    cp /usr/libexec/docker/cli-plugins/docker-compose /usr/lib/docker/cli-plugins/docker-compose && \
    chmod +x /usr/libexec/docker/cli-plugins/docker-compose /usr/lib/docker/cli-plugins/docker-compose && \
    rm -rf /var/lib/apt/lists/*

USER jenkins

# docker-workflow es el id del plugin que el PDF llama "Docker Pipeline".
# El id docker-pipeline no existe en el update center (404).
RUN jenkins-plugin-cli --plugins \
    workflow-aggregator \
    docker-workflow \
    pipeline-stage-view \
    git \
    configuration-as-code \
    job-dsl \
    sonar \
    junit \
    coverage \
    cucumber-reports \
    htmlpublisher \
    lockable-resources

USER root

# Debian 13 mete dockerd en docker.io y deja el cliente en otro paquete que no está
# en el repo. Sin /usr/bin/docker el job de verificación no puede hablar con el socket.
RUN curl -fsSL "https://download.docker.com/linux/static/stable/x86_64/docker-27.5.1.tgz" -o /tmp/docker.tgz && \
    tar -xzf /tmp/docker.tgz -C /tmp && \
    mv /tmp/docker/docker /usr/bin/docker && \
    chmod 755 /usr/bin/docker && \
    rm -rf /tmp/docker /tmp/docker.tgz

RUN command -v runuser && command -v tini && command -v docker

COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
