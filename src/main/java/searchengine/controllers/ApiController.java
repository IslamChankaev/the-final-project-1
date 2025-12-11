// controllers/ApiController.java
package searchengine.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.statistics.ApiResponse;
import searchengine.dto.statistics.SearchResponse;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {
    private final IndexingService indexingService;
    private final SearchService searchService;
    private final StatisticsService statisticsService;

    @GetMapping("/startIndexing")
    public ResponseEntity<ApiResponse> startIndexing() {
        boolean started = indexingService.startFullIndexing();

        if (started) {
            return ResponseEntity.ok(new ApiResponse(true));
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ApiResponse(false, "Индексация уже запущена"));
        }
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<ApiResponse> stopIndexing() {
        boolean stopped = indexingService.stopIndexing();

        if (stopped) {
            return ResponseEntity.ok(new ApiResponse(true));
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ApiResponse(false, "Индексация не запущена"));
        }
    }

    @PostMapping("/indexPage")
    public ResponseEntity<ApiResponse> indexPage(@RequestParam String url) {
        boolean indexed = indexingService.indexSinglePage(url);

        if (indexed) {
            return ResponseEntity.ok(new ApiResponse(true));
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ApiResponse(false,
                            "Данная страница находится за пределами сайтов, указанных в конфигурационном файле"));
        }
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String query,
            @RequestParam(required = false) String site,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {

        SearchResponse response = searchService.search(query, site, offset, limit);

        if (response.isResult()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of(
                            "result", false,
                            "error", response.getError()
                    ));
        }
    }
}