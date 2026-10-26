FROM rabbitmq:3-management
COPY entrypoint.sh /rabbit-ci.sh
RUN chmod 755 /rabbit-ci.sh
USER root
ENTRYPOINT ["/bin/sh", "/rabbit-ci.sh"]
