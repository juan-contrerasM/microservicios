FROM alpine:3.21
COPY init-sonar.sh /init-sonar.sh
RUN sed -i 's/\r$//' /init-sonar.sh && chmod 755 /init-sonar.sh
ENTRYPOINT ["/bin/sh", "/init-sonar.sh"]
