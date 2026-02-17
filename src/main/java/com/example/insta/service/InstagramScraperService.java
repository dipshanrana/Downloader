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
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
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

        // Environment Control for Hosting (Headless Mode)
        String headlessEnv = System.getenv("HEADLESS");
        if ("true".equalsIgnoreCase(headlessEnv)) {
            options.addArguments("--headless=new");
            log.info("Running Instagram Scraper in HEADLESS mode.");
        } else {
            // Default to headless for stability unless debugging
            options.addArguments("--headless=new");
        }

        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments(
                "user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36");

        WebDriver driver = new ChromeDriver(options);
        try {
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(45));
            driver.get(url);

            // Wait a bit for the page to hydrate
            Thread.sleep(5000);

            // Handle Login Popup if achievable (simple check)
            try {
                if (driver.getCurrentUrl().contains("login")) {
                    log.warn("Instagram redirected to login page. Scraping might fail.");
                }
            } catch (Exception e) {
            }

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
            log.error("Instagram Selenium error: {}", e.getMessage());
            return null;
        } finally {
            if (driver != null)
                driver.quit();
        }
    }

    public PexelsScraperService.ScrapedInfo getScrapedInfo(String instaUrl) throws IOException {
        ExtractionResult result = getPageContentWithSelenium(instaUrl);
        PexelsScraperService.ScrapedInfo info = new PexelsScraperService.ScrapedInfo();
        info.setOriginUrl(instaUrl);

        if (result == null || result.html == null) {
            log.error("Failed to load Instagram page.");
            return info;
        }

        info.setCookies(result.cookies);
        info.setUserAgent(result.userAgent);

        Document doc = Jsoup.parse(result.html);
        info.setTitle(doc.title());

        // 1. Try Meta Tags (Standard)
        Element ogVideo = doc.selectFirst("meta[property='og:video']");
        Element ogImage = doc.selectFirst("meta[property='og:image']");
        Element ogDesc = doc.selectFirst("meta[property='og:description']");
        Element ogTitle = doc.selectFirst("meta[property='og:title']");

        if (ogVideo != null) {
            info.setVideoUrl(ogVideo.attr("content"));
        }

        if (ogImage != null) {
            info.setThumbnailUrl(ogImage.attr("content"));
        }

        if (ogDesc != null) {
            info.setDescription(ogDesc.attr("content"));
        }

        if (ogTitle != null) {
            String titleContent = ogTitle.attr("content");
            info.setAuthorName(titleContent);
        }

        // 2. Try Script Fallback (Shared Data)
        if (info.getVideoUrl() == null) {
            log.info("Instagram: Video URL not found in meta tags, scanning scripts...");
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
                            log.info("Instagram: Found video URL in script");
                            break;
                        }
                    }
                }
            }
        }

        if (info.getVideoUrl() != null) {
            log.info("Instagram Scraper: Found URL: {}", info.getVideoUrl());
        } else {
            log.error("Instagram Scraper: Could not find video URL. Page might be private or login-walled to: {}",
                    result.currentUrl);
        }

        return info;
    }
}
