package com.example.insta.controller;

import com.example.insta.service.PexelsScraperService;
import com.example.insta.service.TikTokScraperService;
import com.example.insta.service.InstagramScraperService;
import com.example.insta.service.YoutubeScraperService;
import com.example.insta.service.VideoDownloaderService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
@RequestMapping("/")
public class VideoScraperController {

    private final VideoDownloaderService videoDownloaderService;
    private final PexelsScraperService pexelsScraperService;
    private final TikTokScraperService tiktokScraperService;
    private final InstagramScraperService instagramScraperService;
    private final YoutubeScraperService youtubeScraperService;

    public VideoScraperController(VideoDownloaderService videoDownloaderService,
            PexelsScraperService pexelsScraperService,
            TikTokScraperService tiktokScraperService,
            InstagramScraperService instagramScraperService,
            YoutubeScraperService youtubeScraperService) {
        this.videoDownloaderService = videoDownloaderService;
        this.pexelsScraperService = pexelsScraperService;
        this.tiktokScraperService = tiktokScraperService;
        this.instagramScraperService = instagramScraperService;
        this.youtubeScraperService = youtubeScraperService;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    /**
     * GET /api/video/info — returns scraped metadata including imageUrls for
     * Instagram posts.
     * The response for Instagram will include:
     * - videoUrl (if it's a video post)
     * - imageUrls (list of image URLs for image/carousel posts)
     * - mediaType: "video" | "image" | "carousel" | "unknown"
     */
    @PostMapping("/api/video/info")
    @ResponseBody
    public ResponseEntity<?> getVideoInfo(@RequestBody VideoRequest request) {
        try {
            String url = request.getVideoUrl();
            if (url.contains("tiktok.com")) {
                return ResponseEntity.ok(tiktokScraperService.getScrapedInfo(url));
            } else if (url.contains("instagram.com")) {
                return ResponseEntity.ok(instagramScraperService.getScrapedInfo(url));
            } else if (url.contains("youtube.com") || url.contains("youtu.be")) {
                return ResponseEntity.ok(youtubeScraperService.getScrapedInfo(url));
            }
            return ResponseEntity.ok(pexelsScraperService.getScrapedInfo(url));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Failed to scrape: " + e.getMessage());
        }
    }

    /**
     * GET /api/image/proxy?url=ENCODED_IMAGE_URL
     * Proxies an Instagram CDN image through the server so the browser can display
     * it
     * without CORS issues. Used for previewing images in the UI.
     * Example: GET /api/image/proxy?url=https%3A%2F%2Fcdninstagram.com%2F...
     */
    @GetMapping("/api/image/proxy")
    @ResponseBody
    public ResponseEntity<byte[]> proxyImage(@RequestParam("url") String encodedUrl) {
        try {
            String imageUrl = URLDecoder.decode(encodedUrl, StandardCharsets.UTF_8);
            if (!imageUrl.startsWith("http")) {
                return ResponseEntity.badRequest().build();
            }

            OkHttpClient proxyClient = new OkHttpClient.Builder()
                    .followRedirects(true)
                    .build();

            Request req = new Request.Builder()
                    .url(imageUrl)
                    .addHeader("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                    .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .addHeader("Referer", "https://www.instagram.com/")
                    .addHeader("Origin", "https://www.instagram.com")
                    .addHeader("Sec-Fetch-Dest", "image")
                    .addHeader("Sec-Fetch-Mode", "no-cors")
                    .addHeader("Sec-Fetch-Site", "cross-site")
                    .build();

            try (Response response = proxyClient.newCall(req).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return ResponseEntity.status(response.code()).build();
                }
                byte[] bytes = response.body().bytes();
                String ct = response.header("Content-Type", "image/jpeg");
                return ResponseEntity.ok()
                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                        .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                        .contentType(MediaType.parseMediaType(ct))
                        .body(bytes);
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * POST /api/image/download — simplest possible image download.
     * Just send {"imageUrl": "https://cdninstagram.com/..."} and get the image file
     * back.
     * No cookies, no userAgent, no other fields required.
     */
    @PostMapping("/api/image/download")
    @ResponseBody
    public ResponseEntity<byte[]> downloadImageSimple(@RequestBody ImageDownloadRequest request) {
        try {
            if (request.getImageUrl() == null || request.getImageUrl().isBlank()) {
                return ResponseEntity.badRequest().build();
            }
            Object[] result = videoDownloaderService.fetchImageBytes(
                    request.getImageUrl(), null, null, "https://www.instagram.com/");
            String contentType = (String) result[0];
            byte[] bytes = (byte[]) result[1];
            String ext = contentType != null && contentType.contains("png") ? ".png"
                    : contentType != null && contentType.contains("webp") ? ".webp" : ".jpg";
            String filename = "instagram_image_" + System.currentTimeMillis() + ext;
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType != null ? contentType : "image/jpeg"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * POST /api/instagram/download
     * Send just {"postUrl": "https://www.instagram.com/p/..."}.
     * Scrapes the post, downloads all images, returns a ZIP file.
     * For single-image posts returns the image directly (not zipped).
     */
    @PostMapping("/api/instagram/download")
    @ResponseBody
    public ResponseEntity<byte[]> downloadInstagramPost(@RequestBody InstagramDownloadRequest request) {
        try {
            String postUrl = request.getPostUrl();
            if (postUrl == null || postUrl.isBlank()) {
                return ResponseEntity.badRequest().build();
            }

            // Step 1: Scrape the post to get all image URLs
            PexelsScraperService.ScrapedInfo info = instagramScraperService.getScrapedInfo(
                    postUrl, request.getBrowserCookies());
            List<String> imageUrls = info.getImageUrls();
            String cookies = info.getCookies();
            String userAgent = info.getUserAgent();

            if (imageUrls == null || imageUrls.isEmpty()) {
                // Maybe it's a video post
                String videoUrl = info.getVideoUrl();
                if (videoUrl != null && !videoUrl.isEmpty()) {
                    return ResponseEntity.status(400)
                            .header("X-Error", "This is a video post. Use the video download endpoint.")
                            .build();
                }
                return ResponseEntity.status(404)
                        .header("X-Error", "No images found in this post. It may be private or login-required.")
                        .build();
            }

            // Step 2: Single image — return directly
            if (imageUrls.size() == 1) {
                Object[] result = videoDownloaderService.fetchImageBytes(
                        imageUrls.get(0), cookies, userAgent, postUrl);
                String contentType = (String) result[0];
                byte[] bytes = (byte[]) result[1];
                String ext = contentType != null && contentType.contains("png") ? ".png"
                        : contentType != null && contentType.contains("webp") ? ".webp" : ".jpg";
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType != null ? contentType : "image/jpeg"))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"instagram_image" + ext + "\"")
                        .body(bytes);
            }

            // Step 3: Multiple images — bundle into ZIP
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                int downloaded = 0;
                for (int i = 0; i < imageUrls.size(); i++) {
                    try {
                        Object[] result = videoDownloaderService.fetchImageBytes(
                                imageUrls.get(i), cookies, userAgent, postUrl);
                        String contentType = (String) result[0];
                        byte[] bytes = (byte[]) result[1];
                        String ext = contentType != null && contentType.contains("png") ? ".png"
                                : contentType != null && contentType.contains("webp") ? ".webp" : ".jpg";
                        ZipEntry entry = new ZipEntry("image_" + (i + 1) + ext);
                        zos.putNextEntry(entry);
                        zos.write(bytes);
                        zos.closeEntry();
                        downloaded++;
                    } catch (Exception e) {
                        System.err.println("Failed to fetch image " + (i + 1) + ": " + e.getMessage());
                    }
                }
                System.out.println("ZIP: downloaded " + downloaded + "/" + imageUrls.size() + " images");
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"instagram_images.zip\"")
                    .body(baos.toByteArray());

        } catch (Exception e) {
            System.err.println("downloadInstagramPost error: " + e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }

    /** Request body for /api/instagram/download */
    public static class InstagramDownloadRequest {
        private String postUrl;
        /**
         * Optional: your browser's Instagram cookies for authenticated access.
         * Get from DevTools → Application → Cookies → instagram.com → copy all as
         * "name=value; name2=value2"
         */
        private String browserCookies;

        public String getPostUrl() {
            return postUrl;
        }

        public void setPostUrl(String postUrl) {
            this.postUrl = postUrl;
        }

        public String getBrowserCookies() {
            return browserCookies;
        }

        public void setBrowserCookies(String browserCookies) {
            this.browserCookies = browserCookies;
        }
    }

    /** Simple request body for /api/image/download */
    public static class ImageDownloadRequest {
        private String imageUrl;

        public String getImageUrl() {
            return imageUrl;
        }

        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
        }
    }

    /**
     * POST /api/video/download — downloads a video or a single image.
     * For Instagram image posts, pass imageUrl in the request body instead of
     * videoUrl.
     * The same endpoint handles both video and image downloads.
     */
    @PostMapping("/api/video/download")
    @ResponseBody
    public ResponseEntity<?> downloadVideo(@RequestBody VideoRequest request) {
        try {
            String url = request.getVideoUrl();

            // --- Instagram image download (single image) — stream bytes directly ---
            if (request.getImageUrl() != null && !request.getImageUrl().isEmpty()) {
                Object[] result = videoDownloaderService.fetchImageBytes(
                        request.getImageUrl(),
                        request.getCookies(),
                        request.getUserAgent(),
                        url != null ? url : "https://www.instagram.com/");
                String contentType = (String) result[0];
                byte[] bytes = (byte[]) result[1];
                String ext = contentType != null && contentType.contains("png") ? ".png"
                        : contentType != null && contentType.contains("webp") ? ".webp" : ".jpg";
                String filename = "instagram_image_" + System.currentTimeMillis() + ext;
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType != null ? contentType : "image/jpeg"))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                        .body(bytes);
            }

            // --- Video download ---
            // If the frontend already has the direct video URL (from a prior
            // /api/video/info call),
            // use it directly to avoid a second expensive scrape.
            String directUrl = request.getDirectVideoUrl();

            if (directUrl == null || directUrl.isEmpty()) {
                // No pre-scraped URL — need to scrape now
                if (url == null || url.isEmpty()) {
                    return ResponseEntity.badRequest().body("No URL provided");
                }
                if (url.contains("tiktok.com")) {
                    PexelsScraperService.ScrapedInfo info = tiktokScraperService.getScrapedInfo(url);
                    directUrl = info.getVideoUrl();
                    request.setCookies(info.getCookies());
                    request.setUserAgent(info.getUserAgent());
                } else if (url.contains("instagram.com")) {
                    PexelsScraperService.ScrapedInfo info = instagramScraperService.getScrapedInfo(url);
                    directUrl = info.getVideoUrl();
                    request.setCookies(info.getCookies());
                    request.setUserAgent(info.getUserAgent());
                } else if (url.contains("youtube.com") || url.contains("youtu.be")) {
                    directUrl = youtubeScraperService.scrapeVideoUrl(url);
                } else {
                    directUrl = pexelsScraperService.scrapeVideoUrl(url);
                }
            }

            if (directUrl == null || directUrl.isEmpty()) {
                return ResponseEntity.status(500).body(
                        "Could not find a video URL. This post may be an image post — use the image download buttons instead.");
            }

            Path downloadedPath = videoDownloaderService.downloadVideo(
                    directUrl,
                    request.getCookies(),
                    request.getUserAgent(),
                    request.getOriginUrl() != null ? request.getOriginUrl()
                            : (url != null && url.contains("tiktok.com") ? url : null));

            Resource resource = new UrlResource(downloadedPath.toUri());

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("video/mp4"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + downloadedPath.getFileName().toString() + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity
                    .status(500)
                    .body("Failed to download: " + e.getMessage());
        }
    }

    /**
     * POST /api/video/download/images — downloads ALL images from an Instagram
     * carousel post.
     * Returns a JSON list of download results (filename + status).
     * Endpoint is NEW but uses same base path pattern for consistency.
     */
    @PostMapping("/api/video/download/images")
    @ResponseBody
    public ResponseEntity<?> downloadAllImages(@RequestBody VideoRequest request) {
        try {
            List<Map<String, String>> results = new ArrayList<>();
            List<String> imageUrls = request.getImageUrls();

            if (imageUrls == null || imageUrls.isEmpty()) {
                return ResponseEntity.badRequest().body("No image URLs provided");
            }

            String referer = request.getVideoUrl() != null ? request.getVideoUrl() : "https://www.instagram.com/";

            for (int i = 0; i < imageUrls.size(); i++) {
                String imgUrl = imageUrls.get(i);
                try {
                    Path downloadedPath = videoDownloaderService.downloadImage(
                            imgUrl,
                            request.getCookies(),
                            request.getUserAgent(),
                            referer);
                    results.add(Map.of(
                            "index", String.valueOf(i + 1),
                            "status", "success",
                            "filename", downloadedPath.getFileName().toString()));
                } catch (Exception e) {
                    results.add(Map.of(
                            "index", String.valueOf(i + 1),
                            "status", "failed",
                            "error", e.getMessage()));
                }
            }

            return ResponseEntity.ok(results);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Failed to download images: " + e.getMessage());
        }
    }

    public static class VideoRequest {
        private String videoUrl; // original post URL
        private String directVideoUrl; // pre-scraped direct video stream URL (avoids re-scraping)
        private String imageUrl; // single image URL (for image posts)
        private List<String> imageUrls; // multiple image URLs (for carousel posts)
        private String cookies;
        private String userAgent;
        private String originUrl;

        public String getVideoUrl() {
            return videoUrl;
        }

        public void setVideoUrl(String videoUrl) {
            this.videoUrl = videoUrl;
        }

        public String getDirectVideoUrl() {
            return directVideoUrl;
        }

        public void setDirectVideoUrl(String directVideoUrl) {
            this.directVideoUrl = directVideoUrl;
        }

        public String getImageUrl() {
            return imageUrl;
        }

        public void setImageUrl(String imageUrl) {
            this.imageUrl = imageUrl;
        }

        public List<String> getImageUrls() {
            return imageUrls;
        }

        public void setImageUrls(List<String> imageUrls) {
            this.imageUrls = imageUrls;
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
    }
}
