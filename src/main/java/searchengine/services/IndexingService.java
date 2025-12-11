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

        // Используем sitesList.getSites() для получения списка SiteConfig
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

    // Изменяем тип параметра с SitesList.SiteConfig на SiteConfig
    private void indexSite(SiteConfig siteConfig) {
        try {
            // Удаляем старые данные
            Site site = siteRepository.findByUrl(siteConfig.getUrl())
                    .orElse(createNewSite(siteConfig));
            cleanSiteData(site);

            // Обновляем статус
            site.setStatus(Status.INDEXING);
            site.setLastError(null);
            siteRepository.save(site);

            // Рекурсивный обход страниц
            Set<String> visitedUrls = Collections.synchronizedSet(new HashSet<>());
            SiteParser.PageParseTask task = new SiteParser.PageParseTask(
                    siteConfig.getUrl(), siteConfig.getUrl(), siteParser, visitedUrls, 0);

            Set<String> allUrls = forkJoinPool.invoke(task);

            // Индексируем каждую страницу
            for (String url : allUrls) {
                if (!isIndexing.get()) {
                    break;
                }
                indexPage(url, site);
            }

            if (isIndexing.get()) {
                site.setStatus(Status.INDEXED);
                siteRepository.save(site);
            }

        } catch (Exception e) {
            log.error("Error indexing site: {}", siteConfig.getUrl(), e);
            updateSiteStatus(siteConfig.getUrl(), Status.FAILED,
                    "Ошибка индексации: " + e.getMessage());
        }
    }

    // Изменяем тип параметра с SitesList.SiteConfig на SiteConfig
    private Site createNewSite(SiteConfig siteConfig) {
        Site site = new Site();
        site.setUrl(siteConfig.getUrl());
        site.setName(siteConfig.getName());
        site.setStatus(Status.INDEXING);
        site.setStatusTime(LocalDateTime.now());
        return siteRepository.save(site);
    }

    public boolean indexSinglePage(String url) {
        // Проверяем, принадлежит ли URL одному из сайтов в конфигурации
        // Используем sitesList.getSites() для доступа к списку SiteConfig
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

            // Удаляем старые данные по этой странице
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
                // Удаляем все индексы, связанные со страницами сайта
                List<Page> pages = pageRepository.findBySite(site);
                for (Page page : pages) {
                    List<Index> indexes = indexRepository.findByPage(page);
                    indexRepository.deleteAll(indexes);
                }

                // Удаляем все страницы сайта
                pageRepository.deleteAll(pages);

                // Удаляем все леммы сайта
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
                log.debug("Indexing page: {}", url);

                // Получаем данные страницы через SiteParser
                SiteParser.PageData pageData = siteParser.getPageData(url);
                int statusCode = pageData.getStatusCode();
                Document doc = pageData.getDocument();

                // Извлекаем путь из URL
                String path = extractPath(url, site.getUrl());

                // Проверяем, существует ли уже эта страница
                Optional<Page> existingPage = pageRepository.findByPathAndSite(path, site);

                Page page;
                if (existingPage.isPresent()) {
                    // Обновляем существующую страницу
                    page = existingPage.get();
                    deletePageData(page); // Удаляем старые данные
                } else {
                    // Создаем новую страницу
                    page = new Page();
                    page.setSite(site);
                    page.setPath(path);
                }

                page.setCode(statusCode);
                page.setContent(doc.html());
                pageRepository.save(page);

                // Индексируем только успешные страницы
                if (statusCode == 200) {
                    indexPageContent(page, doc);
                    log.debug("Successfully indexed page: {}", url);
                } else {
                    log.warn("Page returned non-200 status: {} - {}", url, statusCode);
                }

            } catch (Exception e) {
                log.error("Error indexing page: {}", url, e);
                throw new IOException("Failed to index page: " + url, e);
            }
        }

        /**
         * Индексирует контент страницы (леммы и индексы)
         */
        private void indexPageContent(Page page, Document doc) {
            try {
                // Очищаем HTML и извлекаем текст
                String cleanText = textCleaner.cleanHtml(doc.html());

                // Извлекаем леммы из текста
                Map<String, Integer> lemmasMap = lemmaExtractor.extractLemmas(cleanText);

                for (Map.Entry<String, Integer> entry : lemmasMap.entrySet()) {
                    String lemmaText = entry.getKey();
                    int frequency = entry.getValue();

                    // Ищем лемму в базе данных
                    Optional<Lemma> existingLemma = lemmaRepository.findByLemmaAndSite(lemmaText, page.getSite());
                    Lemma lemma;

                    if (existingLemma.isPresent()) {
                        // Увеличиваем частоту существующей леммы
                        lemma = existingLemma.get();
                        lemma.setFrequency(lemma.getFrequency() + 1);
                    } else {
                        // Создаем новую лемму
                        lemma = new Lemma();
                        lemma.setLemma(lemmaText);
                        lemma.setSite(page.getSite());
                        lemma.setFrequency(1);
                    }

                    lemmaRepository.save(lemma);

                    // Создаем или обновляем индекс
                    Index index = new Index();
                    index.setPage(page);
                    index.setLemma(lemma);
                    index.setRank((float) frequency);
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
                // Убедимся, что baseUrl не заканчивается слэшем
                if (baseUrl.endsWith("/")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                }

                // Извлекаем путь
                String path = fullUrl.substring(baseUrl.length());

                // Если путь пустой, возвращаем корень
                if (path.isEmpty()) {
                    return "/";
                }

                return path;

            } catch (Exception e) {
                log.error("Error extracting path from URL: {} (base: {})", fullUrl, baseUrl, e);
                return "/";
            }
        }

        /**
         * Удаляет все данные, связанные со страницей (индексы и саму страницу)
         */
        private void deletePageData(Page page) {
            try {
                // Удаляем все индексы, связанные со страницей
                List<Index> indexes = indexRepository.findByPage(page);
                if (!indexes.isEmpty()) {
                    indexRepository.deleteAll(indexes);
                    log.debug("Deleted {} indexes for page: {}", indexes.size(), page.getPath());
                }

                // Обновляем частоту лемм
                for (Index index : indexes) {
                    Lemma lemma = index.getLemma();
                    lemma.setFrequency(lemma.getFrequency() - 1);

                    // Если частота стала 0, удаляем лемму
                    if (lemma.getFrequency() <= 0) {
                        lemmaRepository.delete(lemma);
                    } else {
                        lemmaRepository.save(lemma);
                    }
                }

                // Удаляем саму страницу
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
    // services/IndexingService.java - добавьте этот метод

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

        // Обновляем статусы всех сайтов, которые были в процессе индексации
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
