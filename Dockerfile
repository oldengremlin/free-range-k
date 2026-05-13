# Stage 1: build fat JAR
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# Cache Gradle dependency downloads separately from source
COPY gradlew gradlew.bat gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

COPY src ./src
RUN ./gradlew jar --no-daemon -q

# Stage 2: slim JRE layer
FROM eclipse-temurin:21-jre-noble AS jre-provider

# Stage 3: nginx + JRE runtime
FROM nginx:mainline
LABEL maintainer="Alexander Russkih <olden@ukr-com.net>"

RUN apt update && \
    apt install -y --no-install-recommends locales locales-all procps openssh-client && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /safe/htpasswords

COPY --from=jre-provider /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="$JAVA_HOME/bin:$PATH"

COPY --from=builder /build/build/libs/free-range-1.0.0.jar \
                    /usr/local/bin/free-range.jar

COPY bin/free-range.sh                      /usr/local/bin/free-range.sh
COPY docker-entrypoint.d/40-free-range.sh   /docker-entrypoint.d/40-free-range.sh

RUN chmod +x /usr/local/bin/free-range.sh \
             /docker-entrypoint.d/40-free-range.sh

ENV LANG=uk_UA.UTF-8
ENV TZ=Europe/Kyiv

EXPOSE 80
