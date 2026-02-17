# ============================================
# Stage 1: Build the application
# ============================================
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# Copy Maven wrapper and pom.xml first for better caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Ensure mvnw has execution permissions and correct line endings
RUN chmod +x mvnw && sed -i 's/\r$//' mvnw

# Download dependencies (this layer will be cached if pom.xml doesn't change)
RUN ./mvnw dependency:go-offline

# Copy source code
COPY src/ ./src/

# Build the application
RUN ./mvnw clean package -DskipTests

# ============================================
# Stage 2: Runtime image
# ============================================
FROM eclipse-temurin:21-jre

# Install Google Chrome and its dependencies
RUN apt-get update && apt-get install -y \
    wget \
    curl \
    gnupg \
    ca-certificates \
    apt-transport-https \
    --no-install-recommends \
    && curl -fsSL https://dl.google.com/linux/linux_signing_key.pub | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome.gpg] http://dl.google.com/linux/chrome/deb/ stable main" > /etc/apt/sources.list.d/google-chrome.list \
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
