# Multi-stage build: the jar is compiled inside the image, so a fresh clone needs only Docker.
#   docker compose up -d --build                      compile with Maven in the build stage (needs Maven Central, or ./.m2/repository)
#   docker build --build-arg JAR_STAGE=prebuilt .     reuse target/heatroute.jar built on the host (offline bundle path)
# A project-local repository ./.m2/repository, when present, is used in offline mode (-o); MAVEN_OFFLINE=0 disables that.
ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-11@sha256:8d3b35643e52d707b16a3e9b52698be1b75c2b45beb5d0e37d35e881f0a18ced
ARG JRE_IMAGE=eclipse-temurin:11.0.24_8-jre-jammy@sha256:89916d58b2d7f6686eed53063c7ed2b191e4d461b6f5ef87e7d5edc103e459a2
ARG JAR_STAGE=build

FROM ${MAVEN_IMAGE} AS build
ARG MAVEN_OFFLINE=auto
WORKDIR /src
COPY . /src
RUN set -e; args="-B -q -Dmaven.test.skip=true"; \
    if [ -d .m2/repository ] && [ "$MAVEN_OFFLINE" != 0 ]; then args="$args -o -Dmaven.repo.local=/src/.m2/repository"; echo "Maven: offline, project-local repository"; fi; \
    mvn $args package; mkdir -p /out; cp target/heatroute.jar /out/heatroute.jar

FROM ${JRE_IMAGE} AS prebuilt
COPY target/heatroute.jar /out/heatroute.jar

FROM ${JAR_STAGE} AS jar

FROM ${JRE_IMAGE}
WORKDIR /app
COPY --from=jar /out/heatroute.jar /app/heatroute.jar
COPY config /app/config
RUN mkdir -p /app/work && chown -R 10001:10001 /app
USER 10001:10001
# Heap follows the container memory limit: 80 % of compose's 5 GB is about 4 GB (recommended for solving).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=80"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/heatroute.jar"]
CMD ["serve"]
