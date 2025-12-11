package searchengine.dto.statistics;

import lombok.Data;

@Data
public class ApiResponse {
    private boolean result;
    private String error;

    public ApiResponse(boolean result) {
        this.result = result;
    }

    public ApiResponse(boolean result, String error) {
        this.result = result;
        this.error = error;
    }
}