ARG SEEDGEN_TAG=latest
ARG SEEDGEN_IMAGE=ghcr.io/ori-community/wotw-seedgen:$SEEDGEN_TAG

FROM gradle:8-jdk21-corretto AS build-jar

WORKDIR /app
COPY . /app

RUN gradle jar


FROM $SEEDGEN_IMAGE AS seedgen


FROM amazoncorretto:21

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

RUN yum -y install shadow-utils && \
    useradd --uid 1010 wotw && \
    chown -R wotw /app

USER wotw

ENTRYPOINT ["/app/entrypoint"]
CMD ["java", "-jar", "/app/server/wotw-server.jar"]
