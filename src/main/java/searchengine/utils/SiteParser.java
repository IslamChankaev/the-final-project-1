package searchengine.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import searchengine.config.CrawlingSettings;

import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RecursiveTask;

@Slf4j
@Component
@RequiredArgsConstructor
public class SiteParser {
    private final CrawlingSettings crawlingSettings;
    private Set<String> visitedUrls = new HashSet<>();

    // Конструктор будет создан автоматически Lombok'ом благодаря @RequiredArgsConstructor
    // Он будет выглядеть так:
    // public SiteParser(CrawlingSettings crawlingSettings) {
    //     this.crawlingSettings = crawlingSettings;
    // }

    /**
     * Получает Connection.Response для URL с настройками
     */
    public Connection.Response getResponse(String url) throws IOException {
        try {
            Thread.sleep(crawlingSettings.getDelayMs());

            Connection connection = Jsoup.connect(url)
                    .userAgent(crawlingSettings.getUserAgent())
                    .referrer(crawlingSettings.getReferrer())
                    .timeout(10000)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true);

            return connection.execute();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Parsing interrupted", e);
        }
    }

    /**
     * Получает Document для URL с настройками
     */
    public Document getDocument(String url) throws IOException {
        Connection.Response response = getResponse(url);
        return response.parse();
    }

    /**
     * Получает Document и код статуса
     */
    public PageData getPageData(String url) throws IOException {
        Connection.Response response = getResponse(url);
        Document doc = response.parse();
        return new PageData(doc, response.statusCode());
    }

    /**
     * Вспомогательный класс для возврата данных страницы
     */
    public static class PageData {
        private final Document document;
        private final int statusCode;

        public PageData(Document document, int statusCode) {
            this.document = document;
            this.statusCode = statusCode;
        }

        public Document getDocument() {
            return document;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    // Геттеры для настроек
    public String getUserAgent() {
        return crawlingSettings.getUserAgent();
    }

    public String getReferrer() {
        return crawlingSettings.getReferrer();
    }

    public int getDelayMs() {
        return crawlingSettings.getDelayMs();
    }

    /**
     * Извлекает ссылки из документа
     */
    public Set<String> extractLinks(Document doc, String baseUrl) {
        Set<String> links = new HashSet<>();

        try {
            Elements linkElements = doc.select("a[href]");

            for (Element link : linkElements) {
                String href = link.attr("abs:href");

                if (isValidLink(href, baseUrl)) {
                    links.add(href);
                }
            }
        } catch (Exception e) {
            log.error("Error extracting links from: {}", baseUrl, e);
        }

        return links;
    }

    private boolean isValidLink(String url, String baseUrl) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        try {
            URL parsedUrl = new URL(url);
            URL parsedBaseUrl = new URL(baseUrl);

            // Проверяем, что ссылка принадлежит тому же домену
            return parsedUrl.getHost().equals(parsedBaseUrl.getHost()) &&
                    !url.contains("#") &&
                    !url.matches(".*\\.(pdf|jpg|png|gif|zip|rar)$");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Рекурсивная задача для обхода страниц
     */
    public static class PageParseTask extends RecursiveTask<Set<String>> {
        private final String url;
        private final String baseUrl;
        private final SiteParser siteParser;
        private final Set<String> visitedUrls;
        private final int depth;
        private final int maxDepth = 3;

        public PageParseTask(String url, String baseUrl, SiteParser siteParser,
                             Set<String> visitedUrls, int depth) {
            this.url = url;
            this.baseUrl = baseUrl;
            this.siteParser = siteParser;
            this.visitedUrls = visitedUrls;
            this.depth = depth;
        }

        @Override
        protected Set<String> compute() {
            Set<String> allLinks = new HashSet<>();

            synchronized (visitedUrls) {
                if (visitedUrls.contains(url) || depth > maxDepth) {
                    return allLinks;
                }
                visitedUrls.add(url);
            }

            try {
                Document doc = siteParser.getDocument(url);
                Set<String> links = siteParser.extractLinks(doc, baseUrl);
                allLinks.addAll(links);

                if (depth < maxDepth) {
                    Set<PageParseTask> tasks = new HashSet<>();

                    for (String link : links) {
                        synchronized (visitedUrls) {
                            if (!visitedUrls.contains(link)) {
                                PageParseTask task = new PageParseTask(
                                        link, baseUrl, siteParser, visitedUrls, depth + 1);
                                task.fork();
                                tasks.add(task);
                            }
                        }
                    }

                    for (PageParseTask task : tasks) {
                        allLinks.addAll(task.join());
                    }
                }

            } catch (IOException e) {
                log.error("Error parsing page: {}", url, e);
            }

            return allLinks;
        }
    }
}