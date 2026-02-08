FROM gradle:8-jdk21-corretto AS build-jar

WORKDIR /app
COPY . /app

RUN gradle jar


FROM amazoncorretto:21

WORKDIR /app

VOLUME /app/seeds

ENV WOTW_DB_HOST=db
ENV WOTW_DB=postgres
ENV WOTW_DB_PORT=5432
ENV WOTW_DB_USER=postgres

COPY --from=build-jar /app/build/libs/wotw-server.jar /app/server/wotw-server.jar
COPY ./entrypoint /app/entrypoint

RUN yum -y install shadow-utils nc && \
    useradd --uid 1010 wotw && \
    chown -R wotw /app

USER wotw

ENTRYPOINT ["/app/entrypoint"]
CMD ["java", "-jar", "/app/server/wotw-server.jar"]
