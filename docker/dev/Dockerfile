# Build sandbox for the Stock Prediction webservice.
# Extends the official Gradle/JDK image with the native runtime XGBoost4J needs
# (libgomp) so model loading and prediction tests run unchanged in Docker.
FROM gradle:8.10.2-jdk21

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*
USER gradle
