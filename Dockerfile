FROM gradle:7-jdk16 as build-jar

WORKDIR /app
COPY . /app

RUN gradle jvmJar


FROM rust:alpine as build-seedgen

WORKDIR /app

RUN apk --no-cache add git musl-dev && \
    git clone --depth 1 https://github.com/sparkle-preference/OriWotwRandomizerClient.git . && \
    cd projects/SeedGenCli && \
    cargo build --release --target-dir /app/build/output


FROM openjdk:16-alpine

WORKDIR /app

VOLUME /app/seeds

ENV SEEDGEN_PATH=/app/seedgen/seedgen
ENV WOTW_DB_HOST=db
ENV SEED_DIR=/app/seeds
ENV WOTW_DB=postgres
ENV WOTW_DB_PORT=5432
ENV WOTW_DB_USER=postgres

COPY --from=build-jar /app/build/libs/wotw-server-jvm.jar /app/server/wotw-server.jar
COPY --from=build-seedgen /app/build/output/release/seedgen /app/seedgen/seedgen
COPY --from=build-seedgen /app/projects/SeedGenCli/headers /app/seedgen/headers
COPY --from=build-seedgen /app/projects/SeedGenCli/presets /app/seedgen/presets
COPY --from=build-seedgen /app/projects/SeedGenCli/areas.wotw /app/seedgen/areas.wotw
COPY --from=build-seedgen /app/projects/SeedGenCli/loc_data.csv /app/seedgen/loc_data.csv
COPY --from=build-seedgen /app/projects/SeedGenCli/state_data.csv /app/seedgen/state_data.csv
COPY ./entrypoint /app/entrypoint

RUN adduser -DHu 1010 wotw && \
    chown -R wotw /app

USER wotw

ENTRYPOINT ["/app/entrypoint"]