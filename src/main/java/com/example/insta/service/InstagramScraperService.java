package com.example.insta.service;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class InstagramScraperService {

    public static class ExtractionResult {
        public String html;
        public String cookies;
        public String userAgent;
        public String currentUrl;
        public String videoUrl;
        public List<String> imageUrls = new ArrayList<>();
        public String mediaType;
    }

    private ExtractionResult getPageContentWithSelenium(String url) {
        return getPageContentWithSelenium(url, null);
    }

    /**
     * @param browserCookies Optional cookie string from the user's browser (e.g.
     *                       "sessionid=abc; csrftoken=xyz").
     *                       When provided, cookies are injected before loading the
     *                       post so Instagram
     *                       sees the request as coming from a logged-in user.
     */
    private ExtractionResult getPageContentWithSelenium(String url, String browserCookies) {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();

        String headlessEnv = System.getenv("HEADLESS");
        if ("true".equalsIgnoreCase(headlessEnv)) {
            options.addArguments("--headless=new");
            log.info("Running Instagram Scraper in HEADLESS mode.");
        } else {
            options.addArguments("--headless=new");
        }

        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--window-size=1920,1080");
        options.addArguments(
                "user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36");

        WebDriver driver = new ChromeDriver(options);
        try {
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(45));

            // If browser cookies are provided, inject them so Instagram sees us as logged
            // in
            if (browserCookies != null && !browserCookies.isBlank()) {
                log.info("Instagram: Injecting browser cookies for authenticated access");
                // Must navigate to the domain first before setting cookies
                driver.get("https://www.instagram.com/");
                Thread.sleep(2000);
                // Parse and inject each cookie
                for (String cookiePair : browserCookies.split(";")) {
                    cookiePair = cookiePair.trim();
                    int eq = cookiePair.indexOf('=');
                    if (eq > 0) {
                        String name = cookiePair.substring(0, eq).trim();
                        String value = cookiePair.substring(eq + 1).trim();
                        try {
                            Cookie cookie = new Cookie.Builder(name, value)
                                    .domain(".instagram.com")
                                    .path("/")
                                    .isSecure(true)
                                    .build();
                            driver.manage().addCookie(cookie);
                        } catch (Exception ce) {
                            log.debug("Could not add cookie {}: {}", name, ce.getMessage());
                        }
                    }
                }
                log.info("Instagram: Cookies injected, navigating to post URL");
            }

            driver.get(url);

            // Wait for page to fully hydrate
            Thread.sleep(5000);

            try {
                if (driver.getCurrentUrl().contains("login")) {
                    log.warn("Instagram redirected to login page. Scraping might fail.");
                }
            } catch (Exception ignored) {
            }

            ExtractionResult result = new ExtractionResult();
            result.currentUrl = driver.getCurrentUrl();

            Set<Cookie> seleniumCookies = driver.manage().getCookies();
            result.cookies = seleniumCookies.stream()
                    .map(c -> c.getName() + "=" + c.getValue())
                    .collect(Collectors.joining("; "));

            JavascriptExecutor js = (JavascriptExecutor) driver;
            result.userAgent = (String) js.executeScript("return navigator.userAgent;");

            // -------------------------------------------------------
            // Step 1: Check for video (og:video meta or <video> tag)
            // -------------------------------------------------------
            try {
                Object videoMeta = js.executeScript(
                        "var v = document.querySelector('video');" +
                                "if (v && v.src) return v.src;" +
                                "if (v && v.querySelector('source') && v.querySelector('source').src) return v.querySelector('source').src;"
                                +
                                "var m = document.querySelector('meta[property=\"og:video\"]');" +
                                "return m ? m.getAttribute('content') : null;");

                if (videoMeta != null && !videoMeta.toString().isEmpty()) {
                    String vUrl = videoMeta.toString();
                    if (vUrl.startsWith("http")) {
                        result.videoUrl = vUrl;
                        result.mediaType = "video";
                        log.info("Instagram: Found video URL from DOM/meta tag");
                    }
                }
            } catch (Exception e) {
                log.debug("Video extraction failed: {}", e.getMessage());
            }

            // -------------------------------------------------------
            // Step 2: Collect ALL carousel images by clicking "Next"
            // -------------------------------------------------------
            if (result.videoUrl == null) {
                result.imageUrls = collectAllCarouselImages(driver, js);
                log.info("Instagram: Collected {} image(s) via carousel navigation", result.imageUrls.size());
            }

            // -------------------------------------------------------
            // Step 3: Fallback — scan JSON script tags for display_url
            // -------------------------------------------------------
            if (result.videoUrl == null) {
                try {
                    Object jsonResult = js.executeScript(
                            "var scripts = document.querySelectorAll('script[type=\"application/json\"]');" +
                                    "for (var i = 0; i < scripts.length; i++) {" +
                                    "  var txt = scripts[i].textContent;" +
                                    "  if (txt && txt.includes('display_url')) return txt;" +
                                    "}" +
                                    "return null;");
                    if (jsonResult != null) {
                        List<String> extracted = extractDisplayUrls(jsonResult.toString());
                        result.imageUrls.addAll(extracted);
                        log.info("Instagram: Found {} image(s) from JSON script tags", extracted.size());
                    }
                } catch (Exception e) {
                    log.debug("JSON script extraction failed: {}", e.getMessage());
                }
            }

            // -------------------------------------------------------
            // Step 4: Fallback — scan all inline scripts
            // -------------------------------------------------------
            if (result.videoUrl == null) {
                try {
                    Object scriptData = js.executeScript(
                            "var scripts = document.getElementsByTagName('script');" +
                                    "var combined = '';" +
                                    "for (var i = 0; i < scripts.length; i++) {" +
                                    "  var t = scripts[i].textContent || '';" +
                                    "  if (t.includes('display_url') || t.includes('video_url') || t.includes('.mp4') || t.includes('video_versions')) combined += t;"
                                    +
                                    "}" +
                                    "return combined.length > 0 ? combined : null;");
                    if (scriptData != null) {
                        String combined = scriptData.toString();
                        if (combined.contains("video_url") && result.videoUrl == null) {
                            String vUrl = extractJsonStringValue(combined, "video_url");
                            if (vUrl != null) {
                                result.videoUrl = vUrl;
                                result.mediaType = "video";
                                log.info("Instagram: Found video URL via JSON video_url key");
                            }
                        }

                        // Ultimate fallback: broad search for any .mp4 URL in the scripts
                        if (result.videoUrl == null) {
                            java.util.regex.Matcher m = java.util.regex.Pattern
                                    .compile("[\"'](https?[^\"']+\\.mp4[^\"']*)[\"']")
                                    .matcher(combined);
                            while (m.find()) {
                                String potentialUrl = m.group(1).replace("\\u0026", "&").replace("\\/", "/")
                                        .replace("\\\\", "");
                                if (potentialUrl.startsWith("http") && !potentialUrl.contains("sample")
                                        && !potentialUrl.contains("dummy")) {
                                    result.videoUrl = potentialUrl;
                                    result.mediaType = "video";
                                    log.info("Instagram: Found video URL via broad .mp4 search");
                                    break;
                                }
                            }
                        }

                        result.imageUrls.addAll(extractDisplayUrls(combined));
                        log.info("Instagram: Fallback script scan found {} images", result.imageUrls.size());
                    }
                } catch (Exception e) {
                    log.debug("Script fallback extraction failed: {}", e.getMessage());
                }
            }

            // Deduplicate
            result.imageUrls = result.imageUrls.stream()
                    .distinct()
                    .filter(u -> u.startsWith("http"))
                    .collect(Collectors.toList());

            // Determine media type
            if (result.mediaType == null) {
                if (url.contains("/reel/") || url.contains("/tv/") || url.contains("/v/")) {
                    result.mediaType = "video";
                    log.info("Instagram: URL format indicates it's a video/reel.");
                } else if (result.imageUrls.size() > 1) {
                    result.mediaType = "carousel";
                } else if (!result.imageUrls.isEmpty()) {
                    result.mediaType = "image";
                } else {
                    result.mediaType = "unknown";
                }
            }

            result.html = driver.getPageSource();
            return result;

        } catch (Exception e) {
            log.error("Instagram Selenium error: {}", e.getMessage());
            return null;
        } finally {
            driver.quit();
        }
    }

    /**
     * Navigates through all carousel slides by clicking the "Next" arrow button,
     * collecting the src of the main post image at each step.
     */
    private List<String> collectAllCarouselImages(WebDriver driver, JavascriptExecutor js) {
        List<String> collected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        try {
            // Collect images from the first slide
            collectCurrentImages(js, seen);

            // Try clicking "Next" up to 20 times
            for (int i = 0; i < 20; i++) {
                List<WebElement> nextBtns = new ArrayList<>();
                for (int retry = 0; retry < 3; retry++) {
                    nextBtns = driver.findElements(
                            By.cssSelector("button[aria-label='Next'], button[aria-label='next'], " +
                                    "div[role='button'] > div > svg[aria-label='Next'], " +
                                    "button [aria-label='Next']"));

                    if (nextBtns.isEmpty()) {
                        nextBtns = driver.findElements(By.xpath("//button[.//svg[@aria-label='Next']]"));
                    }
                    if (!nextBtns.isEmpty()) {
                        break;
                    }
                    try {
                        Thread.sleep(500);
                    } catch (Exception ignored) {
                    }
                }

                if (nextBtns.isEmpty()) {
                    log.debug("Carousel: No 'Next' button found after {} slides.", i + 1);
                    break;
                }

                WebElement nextBtn = nextBtns.get(0);
                try {
                    // Sometime the button is covered or not clickable yet
                    js.executeScript("arguments[0].scrollIntoView({block: 'center'});", nextBtn);
                    Thread.sleep(500);
                    // Use modern mouse event dispatch instead of simple click()
                    js.executeScript(
                            "arguments[0].dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, view: window}));",
                            nextBtn);

                    // Wait for the slide animation and new content to load
                    Thread.sleep(2000);
                    collectCurrentImages(js, seen);
                } catch (Exception e) {
                    log.debug("Carousel: Click next failed at slide {}: {}", i + 1, e.getMessage());
                    break;
                }
            }
            collected.addAll(seen);
        } catch (Exception e) {
            log.warn("Carousel navigation error: {}", e.getMessage());
        }

        return collected;
    }

    /**
     * Extracts ALL currently visible post images from the DOM.
     * Instagram carousels often have 2-3 images in the DOM at once (prev, current,
     * next).
     */
    private void collectCurrentImages(JavascriptExecutor js, Set<String> seen) {
        try {
            Object result = js.executeScript(
                    "var urls = [];" +
                            "var candidates = document.querySelectorAll('article img, div[role=\"presentation\"] img, div[role=\"button\"] img');"
                            +
                            "for (var i = 0; i < candidates.length; i++) {" +
                            "  var s = candidates[i].src;" +
                            "  if (s && (s.includes('cdninstagram') || s.includes('fbcdn')) " +
                            "      && !s.includes('150x150') && !s.includes('s150x150')" +
                            "      && !s.includes('s320x320') && !s.includes('s240x240')" +
                            "      && s.startsWith('http')) {" +
                            "    urls.push(s);" +
                            "  }" +
                            "}" +
                            "return urls;");

            if (result instanceof List) {
                List<?> urls = (List<?>) result;
                for (Object u : urls) {
                    if (u != null)
                        seen.add(u.toString());
                }
            }
        } catch (Exception e) {
            log.debug("collectCurrentImages failed: {}", e.getMessage());
        }
    }

    public PexelsScraperService.ScrapedInfo getScrapedInfo(String instaUrl) throws IOException {
        return getScrapedInfo(instaUrl, null);
    }

    /**
     * Scrapes an Instagram post URL using the provided browser cookies for
     * authentication.
     * 
     * @param browserCookies Cookie string from the user's browser (e.g.
     *                       "sessionid=abc; csrftoken=xyz")
     */
    public PexelsScraperService.ScrapedInfo getScrapedInfo(String instaUrl, String browserCookies) throws IOException {
        ExtractionResult result = getPageContentWithSelenium(instaUrl, browserCookies);
        PexelsScraperService.ScrapedInfo info = new PexelsScraperService.ScrapedInfo();
        info.setOriginUrl(instaUrl);

        if (result == null || result.html == null) {
            log.error("Failed to load Instagram page.");
            return info;
        }

        info.setCookies(result.cookies);
        info.setUserAgent(result.userAgent);
        info.setMediaType(result.mediaType);

        if (result.videoUrl != null)
            info.setVideoUrl(result.videoUrl);
        if (!result.imageUrls.isEmpty())
            info.setImageUrls(result.imageUrls);

        // Parse HTML for metadata
        Document doc = Jsoup.parse(result.html);
        info.setTitle(doc.title());

        Element ogImage = doc.selectFirst("meta[property='og:image']");
        Element ogDesc = doc.selectFirst("meta[property='og:description']");
        Element ogTitle = doc.selectFirst("meta[property='og:title']");

        if (ogImage != null)
            info.setThumbnailUrl(ogImage.attr("content"));
        if (ogDesc != null)
            info.setDescription(ogDesc.attr("content"));
        if (ogTitle != null)
            info.setAuthorName(ogTitle.attr("content"));

        if (info.getVideoUrl() == null) {
            Element ogVideo = doc.selectFirst("meta[property='og:video']");
            if (ogVideo != null) {
                info.setVideoUrl(ogVideo.attr("content"));
                info.setMediaType("video");
            }
        }

        // If it's an image post but no high-res images were found in the gallery,
        // fallback to og:image
        if (info.getVideoUrl() == null && info.getImageUrls().isEmpty() && info.getThumbnailUrl() != null) {
            List<String> fallbackImages = new ArrayList<>();
            fallbackImages.add(info.getThumbnailUrl());
            info.setImageUrls(fallbackImages);
            if (info.getMediaType() == null || info.getMediaType().equals("unknown")) {
                info.setMediaType("image");
            }
        }

        if (info.getVideoUrl() != null) {
            log.info("Instagram Scraper: Found video URL");
        } else if (!info.getImageUrls().isEmpty()) {
            log.info("Instagram Scraper: Found {} image(s), mediaType={}", info.getImageUrls().size(),
                    info.getMediaType());
        } else {
            log.error("Instagram Scraper: No media found. currentUrl={}", result.currentUrl);
        }

        return info;
    }

    /** Extracts all display_url values from a JSON string blob. */
    private List<String> extractDisplayUrls(String data) {
        List<String> urls = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"display_url\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(data);
        while (m.find()) {
            String u = m.group(1).replace("\\u0026", "&").replace("\\/", "/");
            if (u.startsWith("http"))
                urls.add(u);
        }
        return urls;
    }

    /** Extracts a single string value for a given JSON key. */
    private String extractJsonStringValue(String data, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(data);
        if (m.find()) {
            return m.group(1).replace("\\u0026", "&").replace("\\/", "/");
        }
        return null;
    }
}
