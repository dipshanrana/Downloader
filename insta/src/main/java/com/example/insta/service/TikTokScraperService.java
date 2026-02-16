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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TikTokScraperService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public static class ExtractionResult {
        public String html;
        public String cookies;
        public String userAgent;
    }

    private ExtractionResult getPageContentWithSelenium(String url) {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments(
                "user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36");

        WebDriver driver = new ChromeDriver(options);
        try {
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(45));
            driver.get(url);
            Thread.sleep(5000);

            ExtractionResult result = new ExtractionResult();
            result.html = driver.getPageSource();

            Set<Cookie> seleniumCookies = driver.manage().getCookies();
            result.cookies = seleniumCookies.stream()
                    .map(c -> c.getName() + "=" + c.getValue())
                    .collect(Collectors.joining("; "));

            // Extract the actual User-Agent from the browser
            JavascriptExecutor js = (JavascriptExecutor) driver;
            result.userAgent = (String) js.executeScript("return navigator.userAgent;");

            return result;
        } catch (Exception e) {
            System.err.println("TikTok Selenium error: " + e.getMessage());
            return null;
        } finally {
            if (driver != null)
                driver.quit();
        }
    }

    public String scrapeVideoUrl(String tiktokUrl) throws IOException {
        ExtractionResult result = getPageContentWithSelenium(tiktokUrl);
        if (result == null || result.html == null)
            throw new IOException("Failed to load TikTok page.");

        Document doc = Jsoup.parse(result.html);

        Element script = doc.getElementById("__UNIVERSAL_DATA_FOR_REHYDRATION__");
        if (script != null) {
            try {
                JsonNode root = objectMapper.readTree(script.data());
                JsonNode videoDetail = root.path("__DEFAULT_SCOPE__").path("webapp.video-detail");
                if (videoDetail != null) {
                    JsonNode playAddr = videoDetail.path("itemInfo").path("itemStruct").path("video").path("playAddr");
                    if (!playAddr.isMissingNode())
                        return playAddr.asText();
                }
            } catch (Exception e) {
            }
        }

        for (Element s : doc.select("script")) {
            String data = s.data();
            if (data.contains("playAddr")) {
                int start = data.indexOf("playAddr\":\"") + 11;
                int end = data.indexOf("\"", start);
                if (start > 10 && end > start) {
                    return data.substring(start, end).replace("\\u002F", "/");
                }
            }
        }

        throw new IOException("Could not find video download link for TikTok.");
    }

    public PexelsScraperService.ScrapedInfo getScrapedInfo(String tiktokUrl) throws IOException {
        ExtractionResult result = getPageContentWithSelenium(tiktokUrl);
        if (result == null || result.html == null)
            throw new IOException("Failed to load TikTok page.");

        Document doc = Jsoup.parse(result.html);
        PexelsScraperService.ScrapedInfo info = new PexelsScraperService.ScrapedInfo();
        info.setTitle(doc.title());
        info.setCookies(result.cookies);
        info.setUserAgent(result.userAgent);
        info.setOriginUrl(tiktokUrl);

        Element script = doc.getElementById("__UNIVERSAL_DATA_FOR_REHYDRATION__");
        if (script != null) {
            try {
                JsonNode root = objectMapper.readTree(script.data());
                JsonNode videoDetail = root.path("__DEFAULT_SCOPE__").path("webapp.video-detail");
                if (videoDetail.has("itemInfo")) {
                    JsonNode item = videoDetail.get("itemInfo").get("itemStruct");
                    info.setVideoUrl(item.path("video").path("playAddr").asText());
                    info.setThumbnailUrl(item.path("video").path("cover").asText());
                    info.setDescription(item.path("desc").asText());
                    info.setAuthorName(item.path("author").path("nickname").asText());
                }
            } catch (Exception e) {
            }
        }

        return info;
    }
}
