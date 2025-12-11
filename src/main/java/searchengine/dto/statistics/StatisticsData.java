package searchengine.dto.statistics;

import lombok.Data;

import java.util.List;

@Data
public class StatisticsData {
    private TotalStatistics total;
    private List<DetailedStatisticsItem> detailed;

    @Data
    public static class TotalStatistics {
        private int sites;
        private long pages;
        private long lemmas;
        private boolean indexing;
    }
}
