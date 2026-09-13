# Stage 1: Build stage
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# Copy pom.xml and source code
COPY pom.xml .
COPY src ./src

# Build executable jar package
RUN mvn clean package -DskipTests -B

# Stage 2: Production Runtime
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create unprivileged user for security
RUN addgroup -S finxgroup && adduser -S finxuser -G finxgroup

# Copy executable jar from builder stage
COPY --from=builder /app/target/finx-backend-1.0.0.jar app.jar

# Set ownership
RUN chown -R finxuser:finxgroup /app

USER finxuser

EXPOSE 8080

ENV JAVA_OPTS="-Xms128m -Xmx256m -XX:MaxMetaspaceSize=128m -Xss512k -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
