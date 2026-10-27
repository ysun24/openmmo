# OpenMMO home image: game server, login server, web gateway (+ browser client and assets) and the
# Pinggy tunnel in one image; the command picks which (see entrypoint.sh).
# Built by deploy/home/build-bundle.sh from a staged context, never from the repo root.

# eclipse-temurin:25-jre-noble (multi-arch index; linux/amd64 is used)
FROM eclipse-temurin:25-jre-noble@sha256:6943902e6c1ca9b4b4b419b44f65cef80e344c1e6248708dcfc90f4fe2d63a2c

ARG OPENSSH_VERSION=1:9.6p1-3ubuntu13.20
RUN apt-get update \
    && apt-get install -y --no-install-recommends "openssh-client=${OPENSSH_VERSION}" \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 openmmo \
    && useradd --no-log-init --uid 10001 --gid openmmo --home-dir /opt/openmmo \
       --shell /usr/sbin/nologin openmmo \
    && mkdir -p /keys /state \
    && chown 10001:10001 /keys /state \
    && chmod 700 /keys /state

WORKDIR /opt/openmmo
COPY decomp/ decomp/
COPY codegen/ codegen/
COPY web/ web/
COPY game/lib/ game/lib/
COPY --chmod=755 game/bin/ game/bin/
COPY login/lib/ login/lib/
COPY --chmod=755 login/bin/ login/bin/
COPY gateway/lib/ gateway/lib/
COPY --chmod=755 gateway/bin/ gateway/bin/
COPY --chmod=755 entrypoint.sh tunnel.sh ./
COPY VERSION ./

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dorg.jooq.no-logo=true -Dorg.jooq.no-tips=true" \
    OPENMMO_KEYS_DIR=/keys

USER 10001:10001
EXPOSE 2106 7777 8080
ENTRYPOINT ["/opt/openmmo/entrypoint.sh"]
CMD ["help"]
