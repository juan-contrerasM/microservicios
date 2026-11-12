FROM rabbitmq:3.13.7-management
COPY docker-entrypoint-secrets.sh /usr/local/bin/docker-entrypoint-secrets.sh
RUN sed -i 's/\r$//' /usr/local/bin/docker-entrypoint-secrets.sh && chmod 755 /usr/local/bin/docker-entrypoint-secrets.sh
ENTRYPOINT ["/usr/local/bin/docker-entrypoint-secrets.sh"]
CMD ["rabbitmq-server"]
