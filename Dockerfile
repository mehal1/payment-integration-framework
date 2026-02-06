# Multi-stage build for Payment Integration Framework
# Use with: docker build -t payment-framework . && docker run --network host -e KAFKA_BOOTSTRAP_SERVERS=localhost:9092 -e REDIS_HOST=localhost -p 8080:8080 payment-framework

FROM eclipse-temurin:17-jre-alpine AS runtime
WORKDIR /app

# Non-root user
RUN adduser -D -u 1000 appuser
USER appuser

COPY --chown=appuser:appuser target/payment-integration-framework-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
