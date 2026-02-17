package com.example.insta.controller;

import com.example.insta.service.PexelsScraperService;
import com.example.insta.service.TikTokScraperService;
import com.example.insta.service.InstagramScraperService;
import com.example.insta.service.YoutubeScraperService;
import com.example.insta.service.VideoDownloaderService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import java.nio.file.Path;

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

    @PostMapping("/api/video/download")
    @ResponseBody
    public ResponseEntity<?> downloadVideo(@RequestBody VideoRequest request) {
        try {
            String url = request.getVideoUrl();
            String directUrl;
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

            Path downloadedPath = videoDownloaderService.downloadVideo(
                    directUrl,
                    request.getCookies(),
                    request.getUserAgent(),
                    request.getOriginUrl() != null ? request.getOriginUrl()
                            : (url.contains("tiktok.com") ? url : null));

            Resource resource = new UrlResource(downloadedPath.toUri());

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("video/mp4"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + downloadedPath.getFileName().toString() + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity
                    .status(500)
                    .body("Failed to download video: " + e.getMessage());
        }
    }

    public static class VideoRequest {
        private String videoUrl;
        private String cookies;
        private String userAgent;
        private String originUrl;

        public String getVideoUrl() {
            return videoUrl;
        }

        public void setVideoUrl(String videoUrl) {
            this.videoUrl = videoUrl;
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
