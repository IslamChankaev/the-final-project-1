package searchengine.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "crawling")
public class CrawlingSettings {
    private String userAgent = "HeliontSearchBot/1.0";
    private String referrer = "http://www.google.com";
    private int delayMs = 1000;
}