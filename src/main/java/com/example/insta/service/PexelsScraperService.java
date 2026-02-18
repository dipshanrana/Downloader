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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PexelsScraperService {

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
        options.addArguments("--disable-extensions");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments(
                "user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36");

        WebDriver driver = new ChromeDriver(options);
        try {
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
            driver.get(url);
            Thread.sleep(2000);

            ExtractionResult result = new ExtractionResult();
            result.html = driver.getPageSource();

            Set<Cookie> seleniumCookies = driver.manage().getCookies();
            result.cookies = seleniumCookies.stream()
                    .map(c -> c.getName() + "=" + c.getValue())
                    .collect(Collectors.joining("; "));

            JavascriptExecutor js = (JavascriptExecutor) driver;
            result.userAgent = (String) js.executeScript("return navigator.userAgent;");

            return result;
        } catch (Exception e) {
            System.err.println("Selenium error: " + e.getMessage());
            return null;
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }
    }

    public String scrapeVideoUrl(String pexelsUrl) throws IOException {
        if (!pexelsUrl.contains("pexels.com")) {
            return pexelsUrl;
        }

        ExtractionResult result = getPageContentWithSelenium(pexelsUrl);
        if (result == null || result.html == null)
            throw new IOException("Failed to load Pexels page with Selenium.");

        Document doc = Jsoup.parse(result.html);

        for (Element script : doc.select("script[type=application/ld+json]")) {
            String jsonContent = script.data();
            try {
                JsonNode root = objectMapper.readTree(jsonContent);
                if (root.has("@type") && root.get("@type").asText().equals("VideoObject")) {
                    if (root.has("contentUrl")) {
                        return root.get("contentUrl").asText();
                    }
                }
            } catch (Exception e) {
            }
        }
        throw new IOException("Could not find video content URL in the page source.");
    }

    public ScrapedInfo getScrapedInfo(String pexelsUrl) throws IOException {
        ExtractionResult result = getPageContentWithSelenium(pexelsUrl);
        if (result == null || result.html == null)
            throw new IOException("Failed to load Pexels page with Selenium.");

        Document doc = Jsoup.parse(result.html);
        ScrapedInfo info = new ScrapedInfo();
        info.setTitle(doc.title());
        info.setCookies(result.cookies);
        info.setUserAgent(result.userAgent);
        info.setOriginUrl(pexelsUrl);

        for (Element script : doc.select("script[type=application/ld+json]")) {
            String jsonContent = script.data();
            try {
                JsonNode root = objectMapper.readTree(jsonContent);
                if (root.has("@type") && root.get("@type").asText().equals("VideoObject")) {
                    if (root.has("contentUrl"))
                        info.setVideoUrl(root.get("contentUrl").asText());
                    if (root.has("thumbnailUrl"))
                        info.setThumbnailUrl(root.get("thumbnailUrl").asText());
                    if (root.has("description"))
                        info.setDescription(root.get("description").asText());
                    if (root.has("author") && root.get("author").has("name")) {
                        info.setAuthorName(root.get("author").get("name").asText());
                    }
                }
            } catch (Exception e) {
            }
        }
        return info;
    }

    public static class ScrapedInfo {
        private String title;
        private String videoUrl;
        private String thumbnailUrl;
        private String authorName;
        private String description;
        private String cookies;
        private String userAgent;
        private String originUrl;
        private List<String> imageUrls = new ArrayList<>();
        private String mediaType; // "video", "image", "carousel"

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getVideoUrl() {
            return videoUrl;
        }

        public void setVideoUrl(String videoUrl) {
            this.videoUrl = videoUrl;
        }

        public String getThumbnailUrl() {
            return thumbnailUrl;
        }

        public void setThumbnailUrl(String thumbnailUrl) {
            this.thumbnailUrl = thumbnailUrl;
        }

        public String getAuthorName() {
            return authorName;
        }

        public void setAuthorName(String authorName) {
            this.authorName = authorName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getCookies() {
            return cookies;
        }

        public void setCookies(String cookies) {
            this.cookies = cookies;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }

        public String getOriginUrl() {
            return originUrl;
        }

        public void setOriginUrl(String originUrl) {
            this.originUrl = originUrl;
        }

        public List<String> getImageUrls() {
            return imageUrls;
        }

        public void setImageUrls(List<String> imageUrls) {
            this.imageUrls = imageUrls;
        }

        public String getMediaType() {
            return mediaType;
        }

        public void setMediaType(String mediaType) {
            this.mediaType = mediaType;
        }
    }
}
