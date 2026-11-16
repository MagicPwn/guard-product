# Runtime image for the Guard server distribution. The NAS already caches
# node:22-bookworm-slim, and the Debian package mirror is used only to install
# the Java runtime and curl for the health check.
FROM node:22-bookworm-slim

ARG DEBIAN_MIRROR=http://mirrors.aliyun.com

RUN sed -i "s|http://deb.debian.org|${DEBIAN_MIRROR}|g" /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=3 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 update \
    && apt-get install -y --no-install-recommends openjdk-17-jre-headless curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 10001 guard \
    && useradd --system --uid 10001 --gid guard --home-dir /nonexistent \
        --shell /usr/sbin/nologin guard

WORKDIR /opt/guard
COPY app/ /opt/guard/
RUN chown -R guard:guard /opt/guard \
    && chmod +x /opt/guard/bin/guard-server

USER guard
EXPOSE 8080
ENTRYPOINT ["/opt/guard/bin/guard-server"]
