ARG SEEDGEN_TAG=latest
ARG SEEDGEN_IMAGE=ghcr.io/ori-rando/wotw-seedgen:$SEEDGEN_TAG

FROM gradle:7-jdk17 as build-jar

WORKDIR /app
COPY . /app

RUN gradle jar


FROM $SEEDGEN_IMAGE as seedgen


FROM openjdk:17-slim

WORKDIR /app

VOLUME /app/seeds

ENV SEEDGEN_PATH=/app/seedgen/seedgen
ENV WOTW_DB_HOST=db
ENV WOTW_DB=postgres
ENV WOTW_DB_PORT=5432
ENV WOTW_DB_USER=postgres

COPY --from=build-jar /app/build/libs/wotw-server.jar /app/server/wotw-server.jar
COPY --from=seedgen /app/ /app/seedgen/
COPY ./entrypoint /app/entrypoint

RUN adduser --no-create-home --disabled-password --uid 1010 wotw && \
    chown -R wotw /app && \
    apt-get update -y && \
    apt-get install netcat -y

USER wotw

ENTRYPOINT ["/app/entrypoint"]
CMD ["java", "-jar", "/app/server/wotw-server.jar"]
