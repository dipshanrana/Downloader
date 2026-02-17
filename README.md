# Video Downloader Service

A production-ready Spring Boot application for downloading videos from TikTok, Instagram, YouTube, and Pexels.

## Features

- **Multi-Platform Support**: TikTok, Instagram, YouTube, Pexels
- **Selenium-Based Scraping**: Handles dynamic content and authentication
- **Configurable Downloads**: Environment-based configuration
- **Docker Support**: Production-ready containerization
- **Professional Logging**: SLF4J-based structured logging

## Prerequisites

- Java 21
- Maven 3.6+
- Docker (for containerized deployment)

## Local Development

### Build the Application

```bash
./mvnw clean package -DskipTests
```

### Run Locally

```bash
java -jar target/insta-0.0.1-SNAPSHOT.jar
```

The application will start on `http://localhost:8086`.

## Docker Deployment

### Build and Run with Docker Compose

```bash
docker-compose up --build
```

### Build Docker Image Manually

```bash
./mvnw clean package -DskipTests
docker build -t insta-downloader .
```

### Run Docker Container

```bash
docker run -d \
  -p 8086:8086 \
  -v $(pwd)/downloads:/app/downloads \
  -e HEADLESS=true \
  -e APP_DOWNLOAD_DIR=/app/downloads \
  --name insta-downloader \
  insta-downloader
```

## Configuration

### Environment Variables

- `HEADLESS`: Set to `true` for headless Chrome (required for Docker)
- `APP_DOWNLOAD_DIR`: Directory where downloaded videos are saved (default: `downloads`)
- `SERVER_PORT`: Application port (default: `8086`)

### application.properties

```properties
spring.application.name=insta
server.port=8086
app.download.dir=${APP_DOWNLOAD_DIR:downloads}
```

## API Endpoints

### Get Video Info

```bash
POST /api/video/info
Content-Type: application/json

{
  "videoUrl": "https://www.tiktok.com/@user/video/123456"
}
```

### Download Video

```bash
POST /api/video/download
Content-Type: application/json

{
  "videoUrl": "https://www.tiktok.com/@user/video/123456"
}
```

## Architecture

- **VideoScraperController**: REST API endpoints
- **TikTokScraperService**: TikTok video extraction
- **InstagramScraperService**: Instagram video extraction
- **YoutubeScraperService**: YouTube video extraction
- **PexelsScraperService**: Pexels video extraction
- **VideoDownloaderService**: Unified download handler with OkHttp and Selenium support

## Troubleshooting

### Chrome/ChromeDriver Issues in Docker

The Dockerfile installs Google Chrome stable. If you encounter issues:

1. Check Chrome version: `google-chrome --version`
2. Ensure `HEADLESS=true` is set
3. Check logs for Selenium errors

### Download Directory Permissions

Ensure the download directory has proper permissions:

```bash
mkdir -p downloads
chmod 755 downloads
```

## License

MIT
