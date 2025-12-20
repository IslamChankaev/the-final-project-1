package searchengine.dto.statistics;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class StatisticsData {
    private TotalStatistics total = new TotalStatistics(); ;
    private List<DetailedStatisticsItem> detailed = new ArrayList<>();;

    @Data
    public static class TotalStatistics {
        private int sites = 0;
        private long pages = 0;
        private long lemmas = 0;
        private boolean indexing = false;
    }
}
