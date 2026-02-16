package com.example.insta.service;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

@Service
public class VideoDownloaderService {

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(60))
            .readTimeout(Duration.ofSeconds(120))
            .followRedirects(true)
            .build();

    public Path downloadVideo(String videoUrl, String cookies, String userAgent, String originUrl) throws Exception {
        if (videoUrl == null || videoUrl.isEmpty()) {
            throw new RuntimeException("Video URL is null or empty");
        }

        // TikTok specific: Use Selenium MAINLY.
        if (isSeleniumTarget(videoUrl) || (originUrl != null && isSeleniumTarget(originUrl))) {
            System.out.println("Attempting Selenium download for URL: " + videoUrl);
            try {
                // Pass null for UA/Cookies to signal "use defaults"
                return downloadWithSelenium(videoUrl, originUrl, null, null);
            } catch (Exception e) {
                System.err.println("Selenium download failed: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Selenium Download Failed: " + e.getMessage(), e);
            }
        }

        // Standard OkHttp logic for other sites
        String ua = (userAgent != null && !userAgent.isEmpty()) ? userAgent
                : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36";

        Request.Builder requestBuilder = new Request.Builder()
                .url(videoUrl)
                .addHeader("User-Agent", ua)
                .addHeader("Accept", "*/*")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Range", "bytes=0-")
                .addHeader("Connection", "keep-alive");

        if (cookies != null && !cookies.isEmpty()) {
            requestBuilder.addHeader("Cookie", cookies);
        }

        if (originUrl != null && !originUrl.isEmpty()) {
            requestBuilder.addHeader("Referer", originUrl);
        } else if (videoUrl.contains("pexels.com")) {
            requestBuilder.addHeader("Referer", "https://www.pexels.com/");
        }

        try (Response response = client.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException(
                        "Failed to download video. HTTP code: " + response.code() + " " + response.message());
            }

            if (response.body() == null) {
                throw new RuntimeException("Empty response body from video URL");
            }

            String fileName = "video_" + System.currentTimeMillis() + ".mp4";
            String contentDisposition = response.header("Content-Disposition");
            if (contentDisposition != null && contentDisposition.contains("filename=")) {
                fileName = contentDisposition.split("filename=")[1].replace("\"", "");
            } else if (videoUrl.lastIndexOf('/') > 0) {
                String urlName = videoUrl.substring(videoUrl.lastIndexOf('/') + 1);
                if (urlName.contains(".mp4")) {
                    fileName = urlName.split("\\?")[0];
                }
            }

            Path outputPath = Paths.get(System.getProperty("user.home"), "Downloads", fileName);
            try (InputStream inputStream = response.body().byteStream()) {
                Files.copy(inputStream, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            System.out.println("Downloaded to: " + outputPath);
            return outputPath;
        }
    }

    private boolean isSeleniumTarget(String url) {
        return url != null
                && (url.contains("tiktok.com") || url.contains("tiktokcdn.com") || url.contains("instagram.com"));
    }

    private Path downloadWithSelenium(String videoUrl, String originUrl, String userAgent, String cookies)
            throws Exception {
        System.out.println("Selenium downloading with VISIBLE browser. Origin: " + originUrl);

        // Initialize targetUrl early to configure options
        String finalTargetUrl = (originUrl != null
                && (originUrl.contains("tiktok.com") || originUrl.contains("instagram.com"))) ? originUrl
                        : videoUrl;

        // Standardize target URL for generic domain checks
        if (!finalTargetUrl.startsWith("http")) {
            finalTargetUrl = "https://" + finalTargetUrl; // Safety
        }
        final String targetUrl = finalTargetUrl; // Effective final for safety

        io.github.bonigarcia.wdm.WebDriverManager.chromedriver().setup();
        org.openqa.selenium.chrome.ChromeOptions options = new org.openqa.selenium.chrome.ChromeOptions();

        // Environment Control for Hosting (Headless Mode)
        String headlessEnv = System.getenv("HEADLESS");
        if ("true".equalsIgnoreCase(headlessEnv)) {
            options.addArguments("--headless=new");
            System.out.println("Running in HEADLESS mode (Server/Docker environment).");
        } else {
            options.addArguments("--start-maximized"); // GUI mode for local testing
        }

        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-web-security");
        options.addArguments("--allow-running-insecure-content");
        options.addArguments("--ignore-certificate-errors");
        options.addArguments("--autoplay-policy=no-user-gesture-required");
        options.addArguments("--window-size=1920,1080"); // Important for headless element visibility

        // Enable performance logging for YouTube network sniffing
        org.openqa.selenium.logging.LoggingPreferences logPrefs = new org.openqa.selenium.logging.LoggingPreferences();
        logPrefs.enable(org.openqa.selenium.logging.LogType.PERFORMANCE, java.util.logging.Level.ALL);
        options.setCapability("goog:loggingPrefs", logPrefs);

        // Mobile Emulation REMOVED to avoid HLS manifests and ensure MP4 streams
        // if (targetUrl.contains("youtube.com") || targetUrl.contains("youtu.be")) {
        // ... }

        org.openqa.selenium.WebDriver driver = new org.openqa.selenium.chrome.ChromeDriver(options);
        try {
            System.out.println("Navigating to: " + targetUrl);
            System.out.println(
                    ">>> IMPORTANT: If a Captcha/Login appears, please solve it manually in the browser window! <<<");

            driver.get(targetUrl);

            // Allow time for page load and potential captcha
            Thread.sleep(5000);
            System.out.println("Page Title: " + driver.getTitle());

            String currentVideoSrc = null;
            org.openqa.selenium.JavascriptExecutor js = (org.openqa.selenium.JavascriptExecutor) driver;

            // --- INSTAGRAM SPECIFIC EXTRACTION ---
            if (targetUrl.contains("instagram.com")) {
                try {
                    // Try 1: Meta Tag (og:video) - Primary
                    String metaScript = "var meta = document.querySelector('meta[property=\"og:video\"]'); return meta ? meta.content : null;";
                    Object metaResult = js.executeScript(metaScript);
                    if (metaResult != null && !metaResult.toString().isEmpty()) {
                        currentVideoSrc = metaResult.toString();
                        System.out.println("Extracted Instagram URL from og:video: " + currentVideoSrc);
                    }

                    // Try 2: Shared Data (JSON) - Fallback
                    if (currentVideoSrc == null) {
                        String jsonScript = "var scripts = document.getElementsByTagName('script');" +
                                "for (var i = 0; i < scripts.length; i++) {" +
                                "  if (scripts[i].innerText.includes('video_url')) {" +
                                "     var match = scripts[i].innerText.match(/\"video_url\":\"(.*?)\"/);" +
                                "     if (match && match[1]) return match[1].replace(/\\\\u0026/g, '&');" +
                                "  }" +
                                "}" +
                                "return null;";
                        Object jsonResult = js.executeScript(jsonScript);
                        if (jsonResult != null) {
                            currentVideoSrc = jsonResult.toString();
                            System.out.println("Extracted Instagram URL from JSON Script: " + currentVideoSrc);
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Instagram extraction error: " + e.getMessage());
                }
            }
            // --- YOUTUBE SPECIFIC EXTRACTION (Network Sniffing) ---
            else if (targetUrl.contains("youtube.com") || targetUrl.contains("youtu.be")) {
                System.out.println("Scanning network logs for YouTube stream...");
                // Force video to play to ensure network request
                try {
                    js.executeScript("var v = document.querySelector('video'); if (v) { v.play(); v.muted = true; }");
                } catch (Exception e) {
                }

                long startTime = System.currentTimeMillis();
                long timeout = 60000; // 60s scan
                String bestUrl = null;

                while (System.currentTimeMillis() - startTime < timeout) {
                    try {
                        org.openqa.selenium.logging.LogEntries entries = driver.manage().logs()
                                .get(org.openqa.selenium.logging.LogType.PERFORMANCE);
                        for (org.openqa.selenium.logging.LogEntry entry : entries) {
                            String message = entry.getMessage();
                            if (message.contains("googlevideo.com") && message.contains("videoplayback")) {
                                // Parse JSON to check for Method and URL
                                // Message format: { "message": { "method": "Network.requestWillBeSent",
                                // "params": { "request": { "url": "...", "method": "..." } } } }

                                // Simple string check for method is risky if it appears elsewhere, but
                                // "method":"GET" is distinct enough in the request object.
                                // Better: look for the url, then look for method in the vicinity or just assume
                                // we strictly want itag 18/22 which are usually GETs.

                                int urlIndex = message.indexOf("\"url\":\"");
                                if (urlIndex != -1) {
                                    int start = urlIndex + 7;
                                    int end = message.indexOf("\"", start);
                                    if (end > start) {
                                        String candidateUrl = message.substring(start, end);

                                        // CLEANUP: Ensure https
                                        if (!candidateUrl.startsWith("http"))
                                            candidateUrl = "https://" + candidateUrl;

                                        // CRITICAL FIX: The error "sabr.malformed_config" often comes from
                                        // reusing a POST url as a GET, or a manifest URL.
                                        // We MUST ensure we are using a GET request.
                                        // The log usually looks like: ... "request":{"headers":{...}, "method":"GET",
                                        // ... "url":"..." ...
                                        // or "url":"...", "method":"GET"

                                        // Let's rely on ITAG 18/22 presence which is the strongest signal of a "simple"
                                        // video file.
                                        // And IGNORE anything with 'range', 'buf', 'rn', which imply chunks.

                                        if (candidateUrl.contains("googlevideo.com")
                                                && candidateUrl.contains("videoplayback")) {

                                            // Filter: Must be video and NOT audio-only
                                            if (!candidateUrl.contains("mime=audio")) {

                                                // STRICT FILTER: Only accept known "Complete File" itags.
                                                // itag 18 = 360p (Audio+Video)
                                                // itag 22 = 720p (Audio+Video)
                                                boolean isLegacy = candidateUrl.contains("itag=18")
                                                        || candidateUrl.contains("itag=22");

                                                if (isLegacy) {
                                                    System.out.println(
                                                            "Found Valid Legacy Stream (itag 18/22): " + candidateUrl);
                                                    bestUrl = candidateUrl;
                                                    currentVideoSrc = bestUrl;
                                                    break; // Found it! Stop looking.
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                    }

                    if (currentVideoSrc != null) {
                        break;
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                    }
                }

                // If we strictly enforce itag 18/22, we might fail to find anything for some
                // videos (4k only etc).
                // But better to fail than download a broken file.
                if (currentVideoSrc == null) {
                    throw new RuntimeException("Could not find a valid video stream (Standard MP4 or Non-Range).");
                }
            }
            // --- TIKTOK SPECIFIC EXTRACTION ---
            else if (targetUrl.contains("tiktok.com")) {
                try {
                    // Script to extract video URL from hydration data
                    String extractionScript = "try {" +
                            "  var el = document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');" +
                            "  if (el) {" +
                            "    var json = JSON.parse(el.textContent);" +
                            "    var details = json.__DEFAULT_SCOPE__['webapp.video-detail'];" +
                            "    if (details && details.itemInfo && details.itemInfo.itemStruct) {" +
                            "       return details.itemInfo.itemStruct.video.playAddr;" +
                            "    }" +
                            "  }" +
                            "} catch(e) { return null; }" +
                            "return null;";

                    Object jsonResult = js.executeScript(extractionScript);
                    if (jsonResult != null && !jsonResult.toString().isEmpty()) {
                        currentVideoSrc = jsonResult.toString();
                        System.out.println("Extracted video URL from JSON Data: " + currentVideoSrc);
                    }
                } catch (Exception e) {
                    System.out.println("JSON extraction failed: " + e.getMessage());
                }
            }

            // --- UNIVERSAL FALLBACK (DOM) ---
            if (currentVideoSrc == null) {
                System.out.println("JSON/Meta URL not found, trying DOM...");
                try {
                    org.openqa.selenium.support.ui.WebDriverWait wait = new org.openqa.selenium.support.ui.WebDriverWait(
                            driver, Duration.ofSeconds(60));
                    // Wait for video that has a src attribute
                    org.openqa.selenium.WebElement videoElement = wait
                            .until(d -> d.findElement(org.openqa.selenium.By.cssSelector("video[src]")));

                    currentVideoSrc = videoElement.getAttribute("src");
                    System.out.println("Found active video source from DOM: " + currentVideoSrc);
                } catch (Exception e) {
                    System.out.println(
                            "Could not find video element with src. TIMEOUT (60s). Page Title: " + driver.getTitle());
                    throw new RuntimeException(
                            "Could not locate video element. If you saw a Captcha, it wasn't solved in time.");
                }
            }

            if (currentVideoSrc != null && currentVideoSrc.startsWith("blob:")) {
                throw new RuntimeException("Detected 'blob:' URL. Failed to extract real MP4 link.");
            }

            // --- CHANGED: CDP Download with HEADERS ---
            System.out.println("URL Found: " + currentVideoSrc);
            // 3. Configure Download Behavior
            System.out.println("Switching to Browser-Native Download via CDP with Referer Injection...");

            Path downloadDirPath = Paths.get(System.getProperty("user.home"), "Downloads");
            if (!Files.exists(downloadDirPath)) {
                Files.createDirectories(downloadDirPath);
                System.out.println("Created download directory: " + downloadDirPath);
            }
            String downloadDir = downloadDirPath.toAbsolutePath().toString();

            org.openqa.selenium.chromium.ChromiumDriver cDriver = (org.openqa.selenium.chromium.ChromiumDriver) driver;

            // 1. Enable Network Domain
            cDriver.executeCdpCommand("Network.enable", java.util.Collections.emptyMap());

            // 2. Set Extra Headers (Referer is critical for 403 avoidance)
            java.util.Map<String, Object> headers = new java.util.HashMap<>();
            headers.put("Referer", "https://www.youtube.com/");
            headers.put("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

            java.util.Map<String, Object> headerParams = new java.util.HashMap<>();
            headerParams.put("headers", headers);
            cDriver.executeCdpCommand("Network.setExtraHTTPHeaders", headerParams);

            // 3. Configure Download Behavior
            java.util.Map<String, Object> dlParams = new java.util.HashMap<>();
            dlParams.put("behavior", "allow");
            dlParams.put("downloadPath", downloadDir);
            cDriver.executeCdpCommand("Page.setDownloadBehavior", dlParams);

            // 4. Trigger Download by Direct Navigation (in same tab to ensure headers
            // apply)
            System.out.println("Navigating to video URL to trigger download...");
            driver.get(currentVideoSrc);

            // 5. Monitor Download Directory
            System.out.println("Waiting for download to complete in: " + downloadDir);

            Path downloadedFile = waitForDownload(downloadDir, 300); // 5 mins

            if (downloadedFile == null) {
                throw new RuntimeException("Download timed out or failed to start.");
            }

            System.out.println("Browser Downloaded to: " + downloadedFile);

            try {
                String namingPrefix = targetUrl.contains("instagram.com") ? "instagram_"
                        : targetUrl.contains("tiktok.com") ? "tiktok_" : "youtube_";
                String newName = namingPrefix + System.currentTimeMillis() + ".mp4";
                Path targetPath = Paths.get(downloadDir, newName);
                Files.move(downloadedFile, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return targetPath;
            } catch (Exception e) {
                System.out.println("Failed to rename file, returning original: " + e.getMessage());
                return downloadedFile;
            }

        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception e) {
                }
            }
        }
    }

    // Helper to monitor downloads
    private Path waitForDownload(String dir, int timeoutSeconds) throws InterruptedException {
        Path dirPath = Paths.get(dir);
        long endTime = System.currentTimeMillis() + (timeoutSeconds * 1000);

        while (System.currentTimeMillis() < endTime) {
            try {
                // Find most recent file
                java.util.Optional<Path> lastModifiedStats = Files.list(dirPath)
                        .filter(f -> !Files.isDirectory(f))
                        .max((f1, f2) -> {
                            try {
                                return Files.getLastModifiedTime(f1).compareTo(Files.getLastModifiedTime(f2));
                            } catch (java.io.IOException e) {
                                return 0;
                            }
                        });

                if (lastModifiedStats.isPresent()) {
                    Path file = lastModifiedStats.get();
                    String fileName = file.getFileName().toString();

                    // Check if it's our likely suspect (video file or generic name) created
                    // recently
                    // And make sure it's not a temp download file (.crdownload)
                    if (Files.getLastModifiedTime(file).toMillis() > (System.currentTimeMillis() - 60000)) { // Created
                                                                                                             // in last
                                                                                                             // 60s
                        if (!fileName.endsWith(".crdownload") && !fileName.endsWith(".tmp")) {
                            // Assuming this is it.
                            // Check size to be safe
                            if (Files.size(file) > 10000) { // > 10KB
                                return file;
                            }
                        } else {
                            System.out.println("Downloading... (" + fileName + ")");
                        }
                    }
                }
                Thread.sleep(2000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }
}
