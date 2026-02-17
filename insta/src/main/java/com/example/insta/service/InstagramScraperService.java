package com.example.insta.service;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class InstagramScraperService {

    public static class ExtractionResult {
        public String html;
        public String cookies;
        public String userAgent;
        public String currentUrl;
    }

    private ExtractionResult getPageContentWithSelenium(String url) {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new"); // Use headless for scraping metadata
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1920,1080");
        options.addArguments(
                "user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36");

        WebDriver driver = new ChromeDriver(options);
        try {
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(45));
            driver.get(url);

            // Wait a bit for the page to hydrate
            Thread.sleep(5000);

            ExtractionResult result = new ExtractionResult();
            result.html = driver.getPageSource();
            result.currentUrl = driver.getCurrentUrl();

            Set<Cookie> seleniumCookies = driver.manage().getCookies();
            result.cookies = seleniumCookies.stream()
                    .map(c -> c.getName() + "=" + c.getValue())
                    .collect(Collectors.joining("; "));

            JavascriptExecutor js = (JavascriptExecutor) driver;
            result.userAgent = (String) js.executeScript("return navigator.userAgent;");

            return result;
        } catch (Exception e) {
            System.err.println("Instagram Selenium error: " + e.getMessage());
            return null;
        } finally {
            if (driver != null)
                driver.quit();
        }
    }

    // Main method to get video URL (called by Controller if needed, though we might
    // use optimal path)
    public String scrapeVideoUrl(String instaUrl) throws IOException {
        ExtractionResult result = getPageContentWithSelenium(instaUrl);
        if (result == null || result.html == null)
            throw new IOException("Failed to load Instagram page.");

        Document doc = Jsoup.parse(result.html);

        // Strategy 1: Open Graph Meta Tag (Fastest & Most Reliable)
        Element ogVideo = doc.selectFirst("meta[property='og:video']");
        if (ogVideo != null) {
            String videoUrl = ogVideo.attr("content");
            if (videoUrl != null && !videoUrl.isEmpty()) {
                return videoUrl;
            }
        }

        // Strategy 2: Look for shared data script
        // This is a fallback if meta tag is missing (e.g., login wall hitting
        // differently)
        for (Element script : doc.select("script")) {
            String data = script.data();
            if (data.contains("video_url")) {
                // Quick and dirty regex-like extraction for video_url key
                int start = data.indexOf("\"video_url\":\"");
                if (start != -1) {
                    start += 13;
                    int end = data.indexOf("\"", start);
                    if (end > start) {
                        return data.substring(start, end).replace("\\u0026", "&");
                    }
                }
            }
        }

        throw new IOException("Could not find video download link for Instagram.");
    }

    public PexelsScraperService.ScrapedInfo getScrapedInfo(String instaUrl) throws IOException {
        ExtractionResult result = getPageContentWithSelenium(instaUrl);
        if (result == null || result.html == null)
            throw new IOException("Failed to load Instagram page.");

        Document doc = Jsoup.parse(result.html);
        PexelsScraperService.ScrapedInfo info = new PexelsScraperService.ScrapedInfo();

        // 1. Title
        info.setTitle(doc.title());

        // 2. Metadata
        info.setCookies(result.cookies);
        info.setUserAgent(result.userAgent);
        info.setOriginUrl(instaUrl); // Important for referer

        // 3. Video URL
        Element ogVideo = doc.selectFirst("meta[property='og:video']");
        if (ogVideo != null) {
            info.setVideoUrl(ogVideo.attr("content"));
        }

        // 4. Thumbnail
        Element ogImage = doc.selectFirst("meta[property='og:image']");
        if (ogImage != null) {
            info.setThumbnailUrl(ogImage.attr("content"));
        }

        // 5. Description
        Element ogDesc = doc.selectFirst("meta[property='og:description']");
        if (ogDesc != null) {
            info.setDescription(ogDesc.attr("content"));
        }

        // Fallback: Look for shared data script if video URL is still null
        if (info.getVideoUrl() == null) {
            for (Element script : doc.select("script")) {
                String data = script.data();
                if (data.contains("video_url")) {
                    int start = data.indexOf("\"video_url\":\"");
                    if (start != -1) {
                        start += 13;
                        int end = data.indexOf("\"", start);
                        if (end > start) {
                            String vUrl = data.substring(start, end).replace("\\u0026", "&");
                            info.setVideoUrl(vUrl);
                            break;
                        }
                    }
                }
            }
        }

        // 6. Author
        // Instagram titles usually format as "Name (@username) on Instagram..."
        String title = doc.title();
        if (title.contains("(@")) {
            int start = title.indexOf("(@") + 2;
            int end = title.indexOf(")", start);
            if (end > start) {
                info.setAuthorName(title.substring(start, end));
            }
        } else {
            info.setAuthorName("Instagram User");
        }

        return info;
    }
}
