package com.invoice.extraction.model;

import lombok.*;

import java.util.*;

@Data
public class ExtractionResult {

    private String tenantKey;                        // 'TV', 'RADIO', 'INCOME_TAX'
    private String tenantName;                       // 'TV Invoice'
    private Map<String, List<ExtractedRow>> blockResults = new LinkedHashMap<>();
    private Double qrFinalAmount;                    // from QR if available
    private double accuracy;
    private List<String> warnings = new ArrayList<>();
    private String status = "SUCCESS";

    /**
     * Calculates overall accuracy as the average completeness across all rows.
     */
    public void calculateAccuracy() {
        int totalFields = 0;
        int presentFields = 0;

        for (List<ExtractedRow> rows : blockResults.values()) {
            for (ExtractedRow row : rows) {
                // Every key that was defined counts as a possible field
                // Every key that has a non-null value counts as extracted
                for (Object value : row.values()) {
                    totalFields++;
                    if (value != null) {
                        presentFields++;
                    }
                }
            }
        }

        this.accuracy = totalFields == 0 ? 0.0 : (presentFields * 100.0) / totalFields;
    }

    public static ExtractionResult empty(String reason) {
        ExtractionResult r = new ExtractionResult();
        r.status = "EMPTY";
        r.warnings.add(reason);
        r.accuracy = 0.0;
        return r;
    }
}