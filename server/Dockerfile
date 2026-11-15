FROM gradle:8.14.3-jdk17 AS build
WORKDIR /src
COPY --chown=gradle:gradle . .
RUN gradle --no-daemon installDist -x test

FROM eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /nonexistent guard
COPY --from=build /src/build/install/guard-server /opt/guard
USER guard
EXPOSE 8080
ENTRYPOINT ["/opt/guard/bin/guard-server"]
