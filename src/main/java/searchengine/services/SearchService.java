// services/SearchService.java
package searchengine.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Service;
import searchengine.dto.statistics.SearchResult;
import searchengine.dto.statistics.SearchResponse;
import searchengine.model.*;
import searchengine.repositories.*;
import searchengine.utils.LemmaExtractor;
import searchengine.utils.TextCleaner;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;
    private final IndexRepository indexRepository;
    private final LemmaExtractor lemmaExtractor;
    private final TextCleaner textCleaner;

    private final double EXCLUDE_THRESHOLD = 0.8;

    public SearchResponse search(String query, String siteUrl, int offset, int limit) {
        SearchResponse response = new SearchResponse();

        if (query == null || query.trim().isEmpty()) {
            response.setResult(false);
            response.setError("Задан пустой поисковый запрос");
            return response;
        }

        try {
            List<Site> sitesToSearch = getSitesToSearch(siteUrl);

            if (sitesToSearch.isEmpty()) {
                response.setResult(false);
                response.setError("Нет проиндексированных сайтов для поиска");
                return response;
            }

            List<SearchResult> allResults = new ArrayList<>();

            for (Site site : sitesToSearch) {
                List<SearchResult> siteResults = searchInSite(query, site);
                allResults.addAll(siteResults);
            }

            allResults.sort((a, b) -> Double.compare(b.getRelevance(), a.getRelevance()));

            int total = allResults.size();
            int end = Math.min(offset + limit, total);
            List<SearchResult> paginatedResults = allResults.subList(
                    Math.min(offset, total), end);

            response.setResult(true);
            response.setCount(total);
            response.setData(paginatedResults);

        } catch (Exception e) {
            log.error("Search error", e);
            response.setResult(false);
            response.setError("Ошибка при выполнении поиска: " + e.getMessage());
        }

        return response;
    }

    private List<Site> getSitesToSearch(String siteUrl) {
        if (siteUrl != null && !siteUrl.isEmpty()) {
            return siteRepository.findByUrl(siteUrl)
                    .filter(s -> s.getStatus() == Status.INDEXED)
                    .map(List::of)
                    .orElse(List.of());
        }

        return siteRepository.findAll().stream()
                .filter(s -> s.getStatus() == Status.INDEXED)
                .collect(Collectors.toList());
    }

    private List<SearchResult> searchInSite(String query, Site site) {

        List<String> queryLemmas = lemmaExtractor.extractQueryLemmas(query);

        if (queryLemmas.isEmpty()) {
            return List.of();
        }


        List<Lemma> siteLemmas = lemmaRepository.findByLemmaInAndSite(queryLemmas, site);
        siteLemmas = filterCommonLemmas(siteLemmas, site);

        if (siteLemmas.isEmpty()) {
            return List.of();
        }

        siteLemmas.sort(Comparator.comparingInt(Lemma::getFrequency));

        List<Page> foundPages = findPagesByLemmas(siteLemmas, site);

        if (foundPages.isEmpty()) {
            return List.of();
        }


        Map<Page, Double> relevanceMap = calculateRelevance(foundPages, siteLemmas);


        return createSearchResults(foundPages, relevanceMap, query, site);
    }

    private List<Lemma> filterCommonLemmas(List<Lemma> lemmas, Site site) {
        if (lemmas.isEmpty()) {
            return lemmas;
        }

        long totalPages = pageRepository.countBySite(site);
        if (totalPages == 0) {
            return List.of();
        }

        return lemmas.stream()
                .filter(lemma -> {
                    double frequencyRatio = (double) lemma.getFrequency() / totalPages;
                    return frequencyRatio <= EXCLUDE_THRESHOLD;
                })
                .collect(Collectors.toList());
    }



    private List<Page> findPagesByLemmas(List<Lemma> lemmas, Site site) {
        if (lemmas.isEmpty()) {
            return List.of();
        }

        Lemma firstLemma = lemmas.get(0);

        List<Page> pages = indexRepository.findByLemma(firstLemma).stream()
                .map(Index::getPage)
                .filter(page -> page.getSite().equals(site))
                .collect(Collectors.toList());

        for (int i = 1; i < lemmas.size() && !pages.isEmpty(); i++) {
            Lemma lemma = lemmas.get(i);
            List<Page> lemmaPages = indexRepository.findByLemma(lemma).stream()
                    .map(Index::getPage)
                    .filter(page -> page.getSite().equals(site))
                    .collect(Collectors.toList());

            pages.retainAll(lemmaPages);
        }

        return pages;
    }

    private Map<Page, Double> calculateRelevance(List<Page> pages, List<Lemma> lemmas) {
        Map<Page, Double> relevanceMap = new HashMap<>();
        double maxRelevance = 0.0;

        for (Page page : pages) {
            Float sumRank = indexRepository.calculateRelevanceForPage(page, lemmas);
            double relevance = sumRank != null ? sumRank : 0.0;
            relevanceMap.put(page, relevance);
            maxRelevance = Math.max(maxRelevance, relevance);
        }


        if (maxRelevance > 0) {
            for (Map.Entry<Page, Double> entry : relevanceMap.entrySet()) {
                entry.setValue(entry.getValue() / maxRelevance);
            }
        }

        return relevanceMap;
    }

    private List<SearchResult> createSearchResults(List<Page> pages,
                                                   Map<Page, Double> relevanceMap,
                                                   String query, Site site) {
        List<SearchResult> results = new ArrayList<>();
        List<String> queryWords = lemmaExtractor.extractQueryLemmas(query);

        for (Page page : pages) {
            SearchResult result = new SearchResult();
            result.setSite(site.getUrl());
            result.setSiteName(site.getName());
            result.setUri(page.getPath());
            result.setTitle(textCleaner.extractTitle(page.getContent()));
            result.setRelevance(relevanceMap.getOrDefault(page, 0.0));


            String cleanText = textCleaner.cleanHtml(page.getContent());
            result.setSnippet(textCleaner.getSnippet(cleanText, queryWords, 200));

            results.add(result);
        }

        return results;
    }
}