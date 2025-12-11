package searchengine.dto.statistics;

import lombok.Data;
import java.util.List;

@Data
public class SearchResponse {
    private boolean result;
    private String error;
    private int count;
    private List<SearchResult> data;
}