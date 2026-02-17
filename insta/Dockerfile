# Multi-stage build for Railway deployment
# Stage 1: Build the application
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# Copy Maven wrapper and pom.xml
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Download dependencies (cached layer)
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY src src

# Build the application
RUN ./mvnw clean package -DskipTests

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre

# Install Chrome for Selenium
RUN apt-get update && apt-get install -y \
    wget \
    gnupg \
    unzip \
    libnss3 \
    libgconf-2-4 \
    libfontconfig1 \
    libx11-xcb1 \
    libxcomposite1 \
    libxcursor1 \
    libxdamage1 \
    libxi6 \
    libxtst6 \
    libxrandr2 \
    libasound2 \
    libatk1.0-0 \
    libgtk-3-0 \
    libxss1 \
    lsb-release \
    xdg-utils \
    && wget -q -O - https://dl.google.com/linux/linux_signing_key.pub | apt-key add - \
    && echo "deb [arch=amd64] http://dl.google.com/linux/chrome/deb/ stable main" >> /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update \
    && apt-get install -y google-chrome-stable \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy JAR from builder stage
COPY --from=builder /build/target/insta-0.0.1-SNAPSHOT.jar app.jar

# Define Environment Variables
ENV HEADLESS=true
ENV APP_DOWNLOAD_DIR=/app/downloads

# Create download directory
RUN mkdir -p /app/downloads

# Expose Port
EXPOSE 8086

# Run Application
ENTRYPOINT ["java", "-jar", "app.jar"]
