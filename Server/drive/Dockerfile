# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src src
RUN chmod +x mvnw \
	&& ./mvnw --batch-mode --no-transfer-progress -DskipTests package \
	&& mv target/drive-*.jar /tmp/app.jar

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
	&& apt-get install -y --no-install-recommends curl \
	&& rm -rf /var/lib/apt/lists/* \
	&& groupadd --system app \
	&& useradd --system --gid app --home-dir /app --create-home app
WORKDIR /app
COPY --from=builder /tmp/app.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
