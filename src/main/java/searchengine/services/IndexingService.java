// services/IndexingService.java (фрагмент)
package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;
import searchengine.config.CrawlingSettings;
import searchengine.config.SitesList;
import searchengine.config.SiteConfig; // Импорт нового класса
import searchengine.model.*;
import searchengine.repositories.*;
import searchengine.utils.LemmaExtractor;
import searchengine.utils.SiteParser;
import searchengine.utils.TextCleaner;

import java.io.IOException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingService {
    private final SitesList sitesList; // Используем SitesList
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaExtractor lemmaExtractor;
    private final SiteParser siteParser;
    private final TextCleaner textCleaner;

    private ForkJoinPool forkJoinPool;
    public final AtomicBoolean isIndexing = new AtomicBoolean(false);

    public boolean isIndexingActive() {
        return isIndexing.get();
    }

    public boolean startFullIndexing() {
        if (isIndexing.get()) {
            return false;
        }

        isIndexing.set(true);
        forkJoinPool = new ForkJoinPool();


        sitesList.getSites().forEach(siteConfig -> {
            CompletableFuture.runAsync(() -> indexSite(siteConfig), forkJoinPool)
                    .thenRun(() -> log.info("Site indexed: {}", siteConfig.getUrl()))
                    .exceptionally(e -> {
                        log.error("Error indexing site: {}", siteConfig.getUrl(), e);
                        return null;
                    });
        });

        return true;
    }


    private void indexSite(SiteConfig siteConfig) {
        String siteUrl = siteConfig.getUrl();

        log.info("=== START indexing site: {} ===", siteUrl);

        try {

            log.debug("Original URL from config: '{}'", siteUrl);
            log.debug("URL length: {}", siteUrl != null ? siteUrl.length() : "null");
            log.debug("URL trimmed: '{}'", siteUrl != null ? siteUrl.trim() : "null");
            log.debug("URL starts with http: {}", siteUrl != null && siteUrl.startsWith("http"));

            if (siteUrl == null || siteUrl.trim().isEmpty()) {
                log.error("Site URL is empty for site: {}", siteConfig.getName());
                updateSiteStatus(siteUrl, Status.FAILED, "URL сайта пустой");
                return;
            }

            siteUrl = normalizeUrlForIndexing(siteUrl);

            List<Site> existingSites = siteRepository.findAllByUrl(siteUrl);
            Site site;

            if (existingSites.isEmpty()) {
                site = createNewSite(siteConfig);
            } else {
                site = existingSites.get(0);

                for (int i = 1; i < existingSites.size(); i++) {
                    cleanSiteData(existingSites.get(i));
                    siteRepository.delete(existingSites.get(i));
                }
            }

            cleanSiteData(site);

            site.setStatus(Status.INDEXING);
            site.setLastError(null);
            site.setUrl(siteUrl);
            siteRepository.save(site);

            Set<String> visitedUrls = Collections.synchronizedSet(new HashSet<>());
            SiteParser.PageParseTask task = new SiteParser.PageParseTask(
                    siteUrl, siteUrl, siteParser, visitedUrls, 0);

            Set<String> allUrls = forkJoinPool.invoke(task);

            log.info("Found {} URLs to index for site: {}", allUrls.size(), siteUrl);

            int successCount = 0;
            int errorCount = 0;

            for (String url : allUrls) {
                if (!isIndexing.get()) {
                    break;
                }

                try {
                    indexPage(url, site);
                    successCount++;
                } catch (Exception e) {
                    errorCount++;
                    log.error("Error indexing URL {}: {}", url, e.getMessage());
                }

                if (successCount % 10 == 0) {
                    site.setStatusTime(LocalDateTime.now());
                    siteRepository.save(site);
                }
            }

            log.info("Indexing completed for {}: {} successful, {} errors",
                    siteUrl, successCount, errorCount);

            if (isIndexing.get()) {
                site.setStatus(Status.INDEXED);
                site.setStatusTime(LocalDateTime.now());
                siteRepository.save(site);
            }

        } catch (Exception e) {
            log.error("=== CRITICAL ERROR in indexSite ===", e);
            updateSiteStatus(siteUrl, Status.FAILED,
                    "Критическая ошибка: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }


    private Site createNewSite(SiteConfig siteConfig) {
        Site site = new Site();
        site.setUrl(siteConfig.getUrl());
        site.setName(siteConfig.getName());
        site.setStatus(Status.INDEXING);
        site.setStatusTime(LocalDateTime.now());
        return siteRepository.save(site);
    }

    public boolean indexSinglePage(String url) {

        Optional<SiteConfig> siteConfigOpt = sitesList.getSites().stream()
                .filter(sc -> url.startsWith(sc.getUrl()))
                .findFirst();

        if (siteConfigOpt.isEmpty()) {
            return false;
        }

        try {
            SiteConfig siteConfig = siteConfigOpt.get();
            Site site = siteRepository.findByUrl(siteConfig.getUrl())
                    .orElseGet(() -> createNewSite(siteConfig));


            String path = extractPath(url, site.getUrl());
            pageRepository.findByPathAndSite(path, site)
                    .ifPresent(this::deletePageData);

            indexPage(url, site);
            return true;

        } catch (Exception e) {
            log.error("Error indexing single page: {}", url, e);
            return false;
        }
    }
    private void cleanSiteData(Site site) {
        try {

                List<Page> pages = pageRepository.findBySite(site);
                for (Page page : pages) {
                    List<Index> indexes = indexRepository.findByPage(page);
                    indexRepository.deleteAll(indexes);
                }


                pageRepository.deleteAll(pages);


                List<Lemma> lemmas = lemmaRepository.findBySite(site);
                lemmaRepository.deleteAll(lemmas);

                log.info("Cleaned data for site: {}", site.getUrl());

            } catch (Exception e) {
                log.error("Error cleaning site data: {}", site.getUrl(), e);
            }
        }

        /**
         * Индексирует одну страницу
         */
        private void indexPage(String url, Site site) throws IOException {
            try {
                if (url == null || url.trim().isEmpty()) {
                    log.warn("Empty URL provided for site: {}", site.getUrl());
                    return;
                }

                url = normalizeUrlForIndexing(url);

                if (!isValidUrl(url)) {
                    log.warn("Invalid URL: {}", url);
                    return;
                }


                SiteParser.PageData pageData = siteParser.getPageData(url);
                int statusCode = pageData.getStatusCode();
                Document doc = pageData.getDocument();

                String path = extractPath(url, site.getUrl());

                Optional<Page> existingPage = pageRepository.findByPathAndSite(path, site);

                Page page;
                if (existingPage.isPresent()) {
                    page = existingPage.get();
                    deletePageData(page);
                } else {

                    page = new Page();
                    page.setSite(site);
                    page.setPath(path);
                }

                page.setCode(statusCode);
                page.setContent(doc.html());
                pageRepository.save(page);


                if (statusCode == 200) {
                    indexPageContent(page, doc);
                    log.debug("Successfully indexed page: {}", url);
                } else {
                    log.warn("Page returned non-200 status: {} - {}", url, statusCode);
                }

            } catch (Exception e) {
                log.error("Error indexing page: {} for site: {}", url, site.getUrl(), e);
            }
        }

    private String normalizeUrlForIndexing(String url) {
        if (url == null) return "";

        url = url.trim();

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }

        int hashIndex = url.indexOf('#');
        if (hashIndex != -1) {
            url = url.substring(0, hashIndex);
        }

        return url;
    }

    private boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }

        try {
            new URL(url);
            return true;
        } catch (Exception e) {
            log.warn("Invalid URL format: {}", url);
            return false;
        }
    }

    /**
         * Индексирует контент страницы (леммы и индексы)
         */
        private void indexPageContent(Page page, Document doc) {
            try {

                String cleanText = textCleaner.cleanHtml(doc.html());
                Map<String, Integer> lemmasMap = lemmaExtractor.extractLemmas(cleanText);

                for (Map.Entry<String, Integer> entry : lemmasMap.entrySet()) {
                    String lemmaText = entry.getKey();
                    int frequency = entry.getValue();

                    Optional<Lemma> existingLemma = lemmaRepository.findByLemmaAndSite(lemmaText, page.getSite());
                    Lemma lemma;

                    if (existingLemma.isPresent()) {

                        lemma = existingLemma.get();
                        lemma.setFrequency(lemma.getFrequency() + 1);
                    } else {

                        lemma = new Lemma();
                        lemma.setLemma(lemmaText);
                        lemma.setSite(page.getSite());
                        lemma.setFrequency(1);
                    }

                    lemmaRepository.save(lemma);


                    Index index = new Index();
                    index.setPage(page);
                    index.setLemma(lemma);
                    index.setRelevance((float) frequency);
                    indexRepository.save(index);
                }

                log.debug("Indexed {} lemmas for page: {}", lemmasMap.size(), page.getPath());

            } catch (Exception e) {
                log.error("Error indexing page content: {}", page.getPath(), e);
            }
        }

        /**
         * Обновляет статус сайта
         */
        private void updateSiteStatus(String url, Status status, String error) {
            try {
                Optional<Site> siteOpt = siteRepository.findByUrl(url);
                if (siteOpt.isPresent()) {
                    Site site = siteOpt.get();
                    site.setStatus(status);
                    site.setStatusTime(LocalDateTime.now());
                    site.setLastError(error);
                    siteRepository.save(site);
                    log.info("Updated site status: {} - {}", url, status);
                }
            } catch (Exception e) {
                log.error("Error updating site status: {}", url, e);
            }
        }

        /**
         * Извлекает путь из полного URL относительно базового URL сайта
         */
        private String extractPath(String fullUrl, String baseUrl) {
            try {
                if (fullUrl == null || fullUrl.isEmpty()) {
                    log.warn("Full URL is null or empty");
                    return "/";
                }

                if (baseUrl == null || baseUrl.isEmpty()) {
                    log.warn("Base URL is null or empty for full URL: {}", fullUrl);
                    return "/";
                }

                String normalizedFullUrl = normalizeUrl(fullUrl);
                String normalizedBaseUrl = normalizeUrl(baseUrl);

                if (!normalizedFullUrl.startsWith(normalizedBaseUrl)) {
                    log.warn("URL {} doesn't start with base URL {}", fullUrl, baseUrl);
                    return "/";
                }

                String path = fullUrl.substring(baseUrl.length());

                if (path.isEmpty()) {
                    return "/";
                }

                if (!path.startsWith("/")) {
                    path = "/" + path;
                }

                int anchorIndex = path.indexOf('#');
                if (anchorIndex != -1) {
                    path = path.substring(0, anchorIndex);
                }

                int queryIndex = path.indexOf('?');
                if (queryIndex != -1) {
                    path = path.substring(0, queryIndex);
                }

                return path;

            } catch (Exception e) {
                log.error("Error extracting path from URL: {} (base: {})", fullUrl, baseUrl, e);
                return "/";
            }
        }

    private String normalizeUrl(String url) {
        if (url == null) return "";

        return url.toLowerCase()
                .replace("http://", "")
                .replace("https://", "")
                .replace("www.", "");
    }

        /**
         * Удаляет все данные, связанные со страницей (индексы и саму страницу)
         */
        private void deletePageData(Page page) {
            try {

                List<Index> indexes = indexRepository.findByPage(page);
                if (!indexes.isEmpty()) {
                    indexRepository.deleteAll(indexes);
                    log.debug("Deleted {} indexes for page: {}", indexes.size(), page.getPath());
                }


                for (Index index : indexes) {
                    Lemma lemma = index.getLemma();
                    lemma.setFrequency(lemma.getFrequency() - 1);


                    if (lemma.getFrequency() <= 0) {
                        lemmaRepository.delete(lemma);
                    } else {
                        lemmaRepository.save(lemma);
                    }
                }


                pageRepository.delete(page);
                log.debug("Deleted page: {}", page.getPath());

            } catch (Exception e) {
                log.error("Error deleting page data: {}", page.getPath(), e);
            }
        }

        /**
         * Обновляет время статуса сайта (используется в процессе индексации)
         */
        private void updateSiteStatusTime(Site site) {
            try {
                site.setStatusTime(LocalDateTime.now());
                siteRepository.save(site);
            } catch (Exception e) {
                log.error("Error updating site status time: {}", site.getUrl(), e);
            }
        }

        /**
         * Проверяет, принадлежит ли URL одному из сайтов в конфигурации
         */
        private boolean isUrlAllowed(String url) {
            return sitesList.getSites().stream()
                    .anyMatch(siteConfig -> url.startsWith(siteConfig.getUrl()));
        }
        
        private Optional<SiteConfig> getSiteConfigForUrl(String url) {
            return sitesList.getSites().stream()
                    .filter(siteConfig -> url.startsWith(siteConfig.getUrl()))
                    .findFirst();
        }


    /**
     * Останавливает текущую индексацию
     */
    public boolean stopIndexing() {
        if (!isIndexing.get()) {
            log.warn("Indexing is not active, nothing to stop");
            return false;
        }

        log.info("Stopping indexing process...");
        isIndexing.set(false);

        // Останавливаем ForkJoinPool если он активен
        if (forkJoinPool != null && !forkJoinPool.isShutdown()) {
            forkJoinPool.shutdownNow();

            try {
                // Ждем завершения всех задач
                if (!forkJoinPool.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("ForkJoinPool did not terminate in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while waiting for ForkJoinPool to terminate", e);
            }
        }


        updateSitesStatusOnStop();

        log.info("Indexing stopped successfully");
        return true;
    }

    /**
     * Обновляет статусы сайтов при остановке индексации
     */
    private void updateSitesStatusOnStop() {
        try {
            // Находим все сайты со статусом INDEXING
            List<Site> indexingSites = siteRepository.findAll().stream()
                    .filter(site -> site.getStatus() == Status.INDEXING)
                    .collect(Collectors.toList());

            for (Site site : indexingSites) {
                site.setStatus(Status.FAILED);
                site.setLastError("Индексация остановлена пользователем");
                site.setStatusTime(LocalDateTime.now());
                siteRepository.save(site);
                log.info("Site {} marked as FAILED (stopped by user)", site.getUrl());
            }

        } catch (Exception e) {
            log.error("Error updating site statuses on stop", e);
        }
    }
}
