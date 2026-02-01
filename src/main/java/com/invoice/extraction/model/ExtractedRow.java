package com.invoice.extraction.model;

import lombok.*;

import java.util.LinkedHashMap;

/**
 * A single extracted row. Fields are stored as a generic map keyed by
 * the field_name from tenant_field_defs. Values are already parsed to
 * their correct type (String, Integer, Double).
 */
@Data
@Getter
@Setter
public class ExtractedRow extends LinkedHashMap<String, Object> {

    @lombok.experimental.Accessors(fluent = true)
    private int score;

    // Convenience accessors — optional, for downstream consumers
    public String getString(String key) {
        return containsKey(key) ? String.valueOf(get(key)) : null;
    }

    public Integer getInteger(String key) {
        return containsKey(key) ? ((Number) get(key)).intValue() : null;
    }

    public Double getDouble(String key) {
        return containsKey(key) ? ((Number) get(key)).doubleValue() : null;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int totalScore) {
        this.score = totalScore;
    }
}