package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import searchengine.config.SiteConfig;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.model.Site;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.IndexingService;
import searchengine.services.StatisticsService;

import java.util.ArrayList;
import java.util.List;

// services/StatisticsServiceImpl.java
@Service
@RequiredArgsConstructor
@Slf4j
public class StatisticsServiceImpl implements StatisticsService {
    private final SitesList sitesList;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexingService indexingService;

    @Override
    public StatisticsResponse getStatistics() {
        StatisticsResponse response = new StatisticsResponse();

        try {
            StatisticsData data = new StatisticsData();

            // Общая статистика
            StatisticsData.TotalStatistics total = data.getTotal();
            total.setSites(sitesList.getSites() != null ? sitesList.getSites().size() : 0);
            total.setPages(pageRepository.count());
            total.setLemmas(lemmaRepository.count());
            total.setIndexing(indexingService.isIndexingActive());

            // Детальная статистика
            List<DetailedStatisticsItem> detailed = new ArrayList<>();

            if (sitesList.getSites() != null) {
                for (SiteConfig siteConfig : sitesList.getSites()) {
                    detailed.add(createDetailedItem(siteConfig));
                }
            }

            data.setDetailed(detailed);
            response.setStatistics(data);

        } catch (Exception e) {
            log.error("Error in getStatistics", e);
            // Возвращаем пустой, но валидный ответ
            response.setResult(false);
            response.setStatistics(new StatisticsData());
        }

        return response;
    }

    private DetailedStatisticsItem createDetailedItem(SiteConfig siteConfig) {
        DetailedStatisticsItem item = new DetailedStatisticsItem();
        item.setUrl(siteConfig.getUrl());
        item.setName(siteConfig.getName());

        try {
            // Безопасный поиск сайта
            List<Site> sites = siteRepository.findAllByUrl(siteConfig.getUrl());
            if (!sites.isEmpty() && sites.get(0) != null) {
                Site site = sites.get(0);
                item.setStatus(site.getStatus() != null ? site.getStatus().name() : "FAILED");

                if (site.getStatusTime() != null) {
                    item.setStatusTime(site.getStatusTime().toEpochSecond(java.time.ZoneOffset.UTC));
                } else {
                    item.setStatusTime(0L);
                }

                item.setError(site.getLastError() != null ? site.getLastError() : "");
                item.setPages(Math.toIntExact(pageRepository.countBySite(site)));
                item.setLemmas(Math.toIntExact(lemmaRepository.countUniqueLemmasBySite(site)));
            } else {
                item.setStatus("FAILED");
                item.setStatusTime(0L);
                item.setError("Сайт не проиндексирован");
                item.setPages(0);
                item.setLemmas(0);
            }
        } catch (Exception e) {
            log.error("Error creating detailed item for: {}", siteConfig.getUrl(), e);
            item.setStatus("FAILED");
            item.setStatusTime(0L);
            item.setError("Ошибка получения данных");
            item.setPages(0);
            item.setLemmas(0);
        }

        return item;
    }
}