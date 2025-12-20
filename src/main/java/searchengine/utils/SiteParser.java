package searchengine.utils;

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
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.RecursiveTask;

@Slf4j
@Component
@RequiredArgsConstructor
public class SiteParser {
    private final CrawlingSettings crawlingSettings;

    /**
     * Получает данные страницы с безопасной обработкой URL
     */
    public PageData getPageData(String url) throws IOException {
        log.debug("getPageData called with URL: '{}'", url);

        if (url == null) {
            throw new IllegalArgumentException("URL is null");
        }

        String cleanedUrl = cleanUrl(url);
        if (cleanedUrl.isEmpty()) {
            throw new IllegalArgumentException("URL is empty after cleaning");
        }

        try {
            Thread.sleep(crawlingSettings.getDelayMs());

            Connection connection = Jsoup.connect(cleanedUrl)
                    .userAgent(crawlingSettings.getUserAgent())
                    .referrer(crawlingSettings.getReferrer())
                    .timeout(10000)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true);

            Connection.Response response = connection.execute();
            Document doc = response.parse();

            log.debug("Successfully fetched page: {}, status: {}", cleanedUrl, response.statusCode());
            return new PageData(doc, response.statusCode());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Parsing interrupted", e);
        } catch (Exception e) {
            log.error("Error fetching page: {}", cleanedUrl, e);
            throw new IOException("Failed to fetch page: " + cleanedUrl, e);
        }
    }

    /**
     * Очищает и нормализует URL
     */
    private String cleanUrl(String url) {
        if (url == null) return "";

        url = url.trim();

        // Удаляем лишние пробелы и управляющие символы
        url = url.replaceAll("\\s+", "");

        // Проверяем, что URL не пустой после очистки
        if (url.isEmpty()) {
            return "";
        }

        // Добавляем протокол, если его нет
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }

        // Удаляем фрагменты (#) и параметры запроса (?)
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();

            if (host == null) {
                return "";
            }

            // Собираем чистый URL
            StringBuilder cleanUrl = new StringBuilder();
            cleanUrl.append(scheme).append("://").append(host);
            if (path != null && !path.isEmpty()) {
                cleanUrl.append(path);
            }

            return cleanUrl.toString();

        } catch (URISyntaxException e) {
            log.warn("Invalid URL syntax: {}, trying to fix...", url);

            // Простая попытка исправить
            int hashIndex = url.indexOf('#');
            if (hashIndex != -1) {
                url = url.substring(0, hashIndex);
            }

            int queryIndex = url.indexOf('?');
            if (queryIndex != -1) {
                url = url.substring(0, queryIndex);
            }

            return url;
        }
    }

    /**
     * Получает Document (для обратной совместимости)
     */
    public Document getDocument(String url) throws IOException {
        return getPageData(url).getDocument();
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

    /**
     * Извлекает ссылки из документа с проверкой
     */
    public Set<String> extractLinks(Document doc, String baseUrl) {
        Set<String> links = new HashSet<>();

        if (doc == null) {
            log.warn("Document is null for baseUrl: {}", baseUrl);
            return links;
        }

        try {
            Elements linkElements = doc.select("a[href]");
            log.debug("Found {} links on page: {}", linkElements.size(), baseUrl);

            for (Element link : linkElements) {
                String href = link.attr("abs:href");

                // Проверяем, что href не null и не пустой
                if (href == null || href.trim().isEmpty()) {
                    continue;
                }

                // Очищаем URL перед добавлением
                String cleanedHref = cleanUrl(href);
                if (!cleanedHref.isEmpty() && isValidLink(cleanedHref, baseUrl)) {
                    links.add(cleanedHref);
                }
            }

            log.debug("After filtering: {} valid links", links.size());

        } catch (Exception e) {
            log.error("Error extracting links from: {}", baseUrl, e);
        }

        return links;
    }

    /**
     * Проверяет, является ли ссылка валидной
     */
    private boolean isValidLink(String url, String baseUrl) {
        if (url == null || url.isEmpty() || baseUrl == null || baseUrl.isEmpty()) {
            return false;
        }

        try {
            URI uri = new URI(url);
            URI baseUri = new URI(baseUrl);

            // Проверяем, что ссылка принадлежит тому же домену
            boolean sameHost = uri.getHost() != null &&
                    uri.getHost().equals(baseUri.getHost());

            // Исключаем якоря и файлы
            boolean notAnchor = !url.contains("#");
            boolean notFile = !url.matches(".*\\.(pdf|jpg|png|gif|zip|rar|doc|docx|xls|xlsx|ppt|pptx)$");

            return sameHost && notAnchor && notFile;

        } catch (URISyntaxException e) {
            log.debug("Invalid URL syntax in isValidLink: {} (base: {})", url, baseUrl);
            return false;
        } catch (Exception e) {
            log.warn("Error validating link: {}", url, e);
            return false;
        }
    }

    /**
     * Рекурсивная задача для обхода страниц (обновлённая)
     */
    public static class PageParseTask extends RecursiveTask<Set<String>> {
        private final String url;
        private final String baseUrl;
        private final SiteParser siteParser;
        private final Set<String> visitedUrls;
        private final int depth;
        private final int maxDepth = 2; // Уменьшим глубину для отладки

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

            log.debug("Parsing page at depth {}: {}", depth, url);

            try {
                Document doc = siteParser.getDocument(url);
                Set<String> links = siteParser.extractLinks(doc, baseUrl);
                allLinks.addAll(links);

                // Добавляем текущий URL
                allLinks.add(url);

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
            } catch (Exception e) {
                log.error("Unexpected error parsing page: {}", url, e);
            }

            return allLinks;
        }
    }
}