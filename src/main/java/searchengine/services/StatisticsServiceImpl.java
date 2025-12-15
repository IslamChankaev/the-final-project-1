// services/StatisticsServiceImpl.java (фрагмент)
package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.config.SitesList;
import searchengine.config.SiteConfig; // Импорт нового класса
import searchengine.dto.statistics.*;
import searchengine.model.Site;
import searchengine.model.Status;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {
    private final SitesList sitesList; // Используем SitesList
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    public final IndexingService indexingService;

    @Override
    public StatisticsResponse getStatistics() {
        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();


        StatisticsData.TotalStatistics total = new StatisticsData.TotalStatistics();
        total.setSites(sitesList.getSites().size()); // sitesList.getSites() возвращает List<SiteConfig>
        total.setPages(pageRepository.count());
        total.setLemmas(lemmaRepository.count());
        total.setIndexing(indexingService.isIndexingActive());
        data.setTotal(total);


        List<DetailedStatisticsItem> detailed = sitesList.getSites().stream()
                .map(this::createDetailedItem)
                .collect(Collectors.toList());
        data.setDetailed(detailed);

        response.setStatistics(data);
        return response;
    }


    private DetailedStatisticsItem createDetailedItem(SiteConfig siteConfig) {
        DetailedStatisticsItem item = new DetailedStatisticsItem();
        item.setUrl(siteConfig.getUrl());
        item.setName(siteConfig.getName());

        siteRepository.findByUrl(siteConfig.getUrl()).ifPresent(site -> {
            item.setStatus(site.getStatus().name());
            item.setStatusTime(site.getStatusTime().toEpochSecond(ZoneOffset.UTC));
            item.setError(site.getLastError());
            item.setPages(Math.toIntExact(pageRepository.countBySite(site)));
            item.setLemmas(Math.toIntExact(lemmaRepository.countUniqueLemmasBySite(site)));
        });


        if (item.getStatus() == null) {
            item.setStatus(Status.FAILED.name());
            item.setStatusTime(0);
            item.setError("Сайт не проиндексирован");
            item.setPages(0);
            item.setLemmas(0);
        }
        return item;
    }
}