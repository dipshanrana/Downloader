package com.example.insta.service;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.logging.LogEntry;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.logging.Level;

@Service
public class YoutubeScraperService {

    public String scrapeVideoUrl(String youtubeUrl) throws IOException {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();

        // Enable performance logging to capture network traffic
        LoggingPreferences logPrefs = new LoggingPreferences();
        logPrefs.enable(LogType.PERFORMANCE, Level.ALL);
        options.setCapability("goog:loggingPrefs", logPrefs);

        // Environment Control for Hosting (Headless Mode)
        String headlessEnv = System.getenv("HEADLESS");
        if ("true".equalsIgnoreCase(headlessEnv)) {
            options.addArguments("--headless=new");
            System.out.println("Running YouTube Scraper in HEADLESS mode.");
        } else {
            // Default to headless for stability in non-GUI environments
            options.addArguments("--headless=new");
        }

        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--start-maximized");
        options.addArguments("--autoplay-policy=no-user-gesture-required"); // Try to force autoplay

        WebDriver driver = new ChromeDriver(options);
        try {
            System.out.println("Navigating to YouTube: " + youtubeUrl);
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(60));
            driver.get(youtubeUrl);

            // Wait for video to start playing (vital for network requests to fire)
            Thread.sleep(5000);

            // Scan logs for video stream
            String videoUrl = null;
            long startTime = System.currentTimeMillis();
            long timeout = 30000; // 30 seconds scanning

            System.out.println("Scanning network logs for video stream...");

            while (System.currentTimeMillis() - startTime < timeout) {
                List<LogEntry> logs = driver.manage().logs().get(LogType.PERFORMANCE).getAll();
                for (LogEntry entry : logs) {
                    String message = entry.getMessage();
                    // YouTube video streams usually come from googlevideo.com and contain
                    // "videoplayback"
                    if (message.contains("googlevideo.com") && message.contains("videoplayback")) {
                        // Extract URL from the JSON log message
                        // The log message is a JSON structure: { "message": { "method": "...",
                        // "params": { "request": { "url": "..." } } } }
                        // We will use a robust substring search for "url":"...googlevideo.com..."

                        int urlIndex = message.indexOf("\"url\":\"");
                        if (urlIndex != -1) {
                            int start = urlIndex + 7;
                            int end = message.indexOf("\"", start);
                            if (end > start) {
                                String candidateUrl = message.substring(start, end);

                                // Basic validation: must be a real HTTP URL and contain videoplayback
                                if (candidateUrl.startsWith("http") && candidateUrl.contains("videoplayback")) {
                                    // Preference: Filter out audio-only if possible, but keep it as a fallback
                                    // Audio streams often have "mime=audio" or "clen=" (content length) which video
                                    // also has
                                    // Let's accept the first valid one for now, as merging audio/video is complex
                                    // without ffmpeg.
                                    // We'll trust that the "videoplayback" request for the main player is usually
                                    // the video.

                                    System.out.println("Candidate URL found: " + candidateUrl);
                                    videoUrl = candidateUrl;
                                    break;
                                }
                            }
                        }
                    }
                }

                if (videoUrl != null) {
                    System.out.println("Found YouTube Video Stream!");
                    break;
                }
                Thread.sleep(1000);
            }

            if (videoUrl == null) {
                System.err.println("Timeout scanning network logs. Last Title: " + driver.getTitle());
                throw new IOException("Could not find video stream. Please ensure the video started playing.");
            }

            return videoUrl;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while scraping");
        } finally {
            if (driver != null) {
                // Don't quit immediately if we found a URL, downloading might need the session?
                // Actually, for this architecture, we return the URL and the Downloader (or
                // Controller) handles the download.
                // However, the Controller calls `scrapeVideoUrl` then returns it to the user?
                // Wait, `VideoScraperController` -> `downloadVideo` -> calls
                // `youtubeScraperService.scrapeVideoUrl`
                // THEN it needs to download it.
                // If `scrapeVideoUrl` returns a string, `VideoScraperController` needs to pass
                // that to `VideoDownloaderService`.
                // BUT `VideoDownloaderService` expects a URL to download using OkHttp (by
                // default) or Selenium.
                // YouTube streams usually require the same cookies/headers (or at least same
                // IP) to download.
                // So using OkHttp on a signed URL *might* work if the signature is in the URL.
                driver.quit();
            }
        }
    }

    public PexelsScraperService.ScrapedInfo getScrapedInfo(String youtubeUrl) {
        // Basic info since we are mainly focused on download
        PexelsScraperService.ScrapedInfo info = new PexelsScraperService.ScrapedInfo();
        info.setOriginUrl(youtubeUrl);
        info.setTitle("YouTube Video");
        info.setAuthorName("YouTube Creator");
        return info;
    }
}
