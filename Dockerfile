FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S catalog && adduser -S catalog -G catalog
WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/application.jar
USER catalog
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseZGC -XX:+ZGenerational -Dreactor.netty.ioWorkerCount=8"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar"]
