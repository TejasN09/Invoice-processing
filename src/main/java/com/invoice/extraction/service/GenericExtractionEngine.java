package com.invoice.extraction.service;

import com.invoice.extraction.entity.*;
import com.invoice.extraction.model.ExtractionResult;
import com.invoice.extraction.model.ExtractedRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * TRULY UNIFIED EXTRACTION ENGINE
 * Direct translation of RadioInvoiceExtractorService.processRows() to be config-driven
 * 
 * THE KEY FIX: Context field detection happens BEFORE row accumulation starts,
 * exactly like the working radio service.
 */
@Service
@Slf4j
public class GenericExtractionEngine {

    private final TenantConfigService configService;
    
    private static final Set<String> TOTAL_LINE_INDICATORS = Set.of(
            "sub total", "grand total", "total for", "net amount", "gross amount"
    );

    public GenericExtractionEngine(TenantConfigService configService) {
        this.configService = configService;
    }

    public ExtractionResult extract(String pdfText, InvoiceTenant tenant) {
        ExtractionResult result = new ExtractionResult();
        result.setTenantKey(tenant.getTenantKey());
        result.setTenantName(tenant.getDisplayName());

        Map<String, List<TenantFieldDef>> fieldsByBlock = tenant.getFieldDefs().stream()
                .collect(Collectors.groupingBy(TenantFieldDef::getBlockName));

        Map<String, TenantBlockConfig> configByBlock = tenant.getBlockConfigs().stream()
                .collect(Collectors.toMap(TenantBlockConfig::getBlockName, c -> c));

        Map<String, String> cityMappings = configService.getCityMappings(tenant.getId());

        for (Map.Entry<String, TenantBlockConfig> entry : configByBlock.entrySet()) {
            String blockName = entry.getKey();
            TenantBlockConfig blockConfig = entry.getValue();
            List<TenantFieldDef> fieldDefs = fieldsByBlock.getOrDefault(blockName, List.of());

            if (fieldDefs.isEmpty()) continue;

            boolean hasContextFields = fieldDefs.stream().anyMatch(TenantFieldDef::isContext);

            List<ExtractedRow> rows = hasContextFields
                    ? extractWithContext(pdfText, blockConfig, fieldDefs, cityMappings)
                    : extractSimple(pdfText, blockConfig, fieldDefs, cityMappings);

            result.getBlockResults().put(blockName, rows);
        }

        result.calculateAccuracy();
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SIMPLE EXTRACTION (TV invoices)
    // ═══════════════════════════════════════════════════════════════════════

    private List<ExtractedRow> extractSimple(
            String pdfText,
            TenantBlockConfig blockConfig,
            List<TenantFieldDef> fieldDefs,
            Map<String, String> cityMappings) {

        List<String> textBlocks = segmentText(pdfText, blockConfig);
        List<ExtractedRow> rows = new ArrayList<>();

        for (String block : textBlocks) {
            ExtractedRow row = extractRow(block, fieldDefs, cityMappings);
            int score = row.getScore();

            if (score >= blockConfig.getMinScore() && passesRequiredCheck(row, fieldDefs)) {
                rows.add(row);
            }
        }

        return rows;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONTEXT-AWARE EXTRACTION (Radio invoices)
    // THIS IS THE EXACT TRANSLATION OF processRows() from RadioInvoiceExtractorService
    // ═══════════════════════════════════════════════════════════════════════

    private List<ExtractedRow> extractWithContext(
            String pdfText,
            TenantBlockConfig blockConfig,
            List<TenantFieldDef> allFieldDefs,
            Map<String, String> cityMappings) {

        // Separate field types
        List<TenantFieldDef> contextFields = allFieldDefs.stream()
                .filter(TenantFieldDef::isContext)
                .sorted(Comparator.comparingInt(TenantFieldDef::getSortOrder))
                .toList();

        List<TenantFieldDef> dataFields = allFieldDefs.stream()
                .filter(f -> !f.isContext())
                .sorted(Comparator.comparingInt(TenantFieldDef::getSortOrder))
                .toList();

        // Find the PRIMARY context field (city) - the one that resets context
        TenantFieldDef primaryContextField = contextFields.stream()
                .filter(TenantFieldDef::isContextResetOnMatch)
                .findFirst()
                .orElse(null);

        List<ExtractedRow> extractedRows = new ArrayList<>();
        String[] lines = pdfText.split("\\n");

        // === EXACT TRANSLATION OF YOUR RADIO SERVICE ===
        
        String currentCity = null;  // Tracks the current context value
        StringBuilder rowBuffer = new StringBuilder(512);

        for (String line : lines) {
            String cleanLine = line.trim();

            if (cleanLine.isEmpty() || isTotalLine(cleanLine)) {
                continue;
            }

            // === STEP 1: Update city context ===
            // THIS IS THE KEY: Detect context BEFORE checking row start
            String detectedCity = detectCity(cleanLine, primaryContextField, cityMappings);
            if (detectedCity != null) {
                finalizeRow(rowBuffer, extractedRows, contextFields, dataFields, 
                           currentCity, blockConfig.getMinScore());
                currentCity = detectedCity;

                // CRITICAL: Check if this line is ALSO a row start
                if (!isRowStart(cleanLine, blockConfig, contextFields)) {
                    continue;
                }
            }

            // === STEP 2: Detect new row start ===
            if (isRowStart(cleanLine, blockConfig, contextFields)) {
                finalizeRow(rowBuffer, extractedRows, contextFields, dataFields, 
                           currentCity, blockConfig.getMinScore());
                rowBuffer.append(cleanLine).append(" ");
            } else if (rowBuffer.length() > 0) {
                // Continue accumulating multi-line row
                rowBuffer.append(cleanLine).append(" ");
            }
        }

        // Flush final row
        finalizeRow(rowBuffer, extractedRows, contextFields, dataFields, 
                   currentCity, blockConfig.getMinScore());

        log.info("Context extraction completed: {} rows extracted", extractedRows.size());
        return extractedRows;
    }

    /**
     * EXACT TRANSLATION OF: detectCity()
     * Detects the primary context field (city or equivalent)
     */
    private String detectCity(String line, TenantFieldDef primaryContextField,
                              Map<String, String> cityMappings) {
        if (primaryContextField == null) {
            return null;
        }

        String extracted = tryExtractField(line, primaryContextField);
        
        if (extracted != null) {
            // Apply city mapping if available (Radio City uses 3-letter codes)
            if (!cityMappings.isEmpty()) {
                String mapped = cityMappings.get(extracted.toUpperCase().trim());
                if (mapped != null) {
                    return mapped;
                }
            }
            return extracted;
        }
        
        return null;
    }

    /**
     * EXACT TRANSLATION OF: isRowStart()
     * Priority 1: Explicit row_start_pattern
     * Priority 2: Date pattern (context field that doesn't reset)
     */
    private boolean isRowStart(String line, TenantBlockConfig blockConfig,
                               List<TenantFieldDef> contextFields) {
        
        // Priority 1: Specific row start pattern (Red FM, Radio City, etc.)
        if (blockConfig.getBlockStartPattern() != null && 
            !blockConfig.getBlockStartPattern().isBlank()) {
            
            Pattern rowStartPattern = compilePattern(blockConfig.getBlockStartPattern());
            if (rowStartPattern != null && rowStartPattern.matcher(line).find()) {
                return true;
            }
        }

        // Priority 2: Date pattern as fallback
        // Look for context fields that DON'T reset (like dateRange)
        for (TenantFieldDef contextField : contextFields) {
            if (!contextField.isContextResetOnMatch()) {
                String extracted = tryExtractField(line, contextField);
                if (extracted != null) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * EXACT TRANSLATION OF: finalizeRow() + extractFieldsFromText()
     */
    private void finalizeRow(StringBuilder buffer, List<ExtractedRow> rows,
                            List<TenantFieldDef> contextFields,
                            List<TenantFieldDef> dataFields,
                            String currentCityValue,
                            int minScore) {
        if (buffer.length() == 0) {
            return;
        }

        String rowText = buffer.toString().trim();
        
        // === YOUR extractFieldsFromText() LOGIC ===
        
        ExtractedRow row = new ExtractedRow();
        int score = 0;

        // 1. Set the primary context (city)
        TenantFieldDef primaryContext = contextFields.stream()
                .filter(TenantFieldDef::isContextResetOnMatch)
                .findFirst()
                .orElse(null);
        
        if (primaryContext != null && currentCityValue != null) {
            row.put(primaryContext.getFieldName(), currentCityValue);
            // Don't add score for context - it's inherited
        }

        // 2. Extract dates (from row text)
        // These are context fields that DON'T reset
        extractDates(rowText, contextFields, row);

        // 3. Extract timeband (from row text)
        extractTimeband(rowText, contextFields, row);

        // 4. Extract numeric fields (spots, rate, amount, etc.)
        extractNumericFields(rowText, dataFields, row, score);

        // 5. Calculate FCT if missing
        calculateFctIfMissing(row);

        row.score(score);

        // Only add rows with meaningful data
        if (hasMinimumData(row)) {
            rows.add(row);
            log.debug("Extracted row: {}", row);
        } else {
            log.debug("Skipped incomplete row: {}", rowText.substring(0, Math.min(60, rowText.length())));
        }

        buffer.setLength(0);
    }

    /**
     * YOUR extractDates() logic
     */
    private void extractDates(String text, List<TenantFieldDef> contextFields, ExtractedRow row) {
        for (TenantFieldDef field : contextFields) {
            if (!field.isContextResetOnMatch() && 
                (field.getFieldName().contains("Date") || field.getFieldName().contains("date"))) {
                
                for (String regex : field.getExtractionPatterns()) {
                    try {
                        Pattern p = compilePattern(regex);
                        if (p == null) continue;
                        
                        Matcher m = p.matcher(text);
                        if (m.find()) {
                            // Group 1: start date
                            if (m.groupCount() >= 1) {
                                String startDate = cleanDateValue(m.group(1));
                                row.put("startDate", startDate);
                            }
                            
                            // Group 2: end date (if present)
                            if (m.groupCount() >= 2 && m.group(2) != null) {
                                String endDate = cleanDateValue(m.group(2));
                                row.put("endDate", endDate);
                            }
                            // Strategy 2: End date in separate match
                            else if (m.find()) {
                                String endDate = cleanDateValue(m.group(1));
                                row.put("endDate", endDate);
                            }
                            
                            break; // Found dates, stop
                        }
                    } catch (Exception e) {
                        // Continue to next pattern
                    }
                }
            }
        }
    }

    /**
     * YOUR extractTimeband() logic
     */
    private void extractTimeband(String text, List<TenantFieldDef> contextFields, ExtractedRow row) {
        for (TenantFieldDef field : contextFields) {
            if (!field.isContextResetOnMatch() && 
                (field.getFieldName().contains("timeband") || field.getFieldName().contains("Timeband"))) {
                
                for (String regex : field.getExtractionPatterns()) {
                    try {
                        Pattern p = compilePattern(regex);
                        if (p == null) continue;
                        
                        Matcher m = p.matcher(text);
                        if (m.find()) {
                            if (m.groupCount() >= 1) {
                                row.put("timebandStart", m.group(1));
                            }
                            if (m.groupCount() >= 2 && m.group(2) != null) {
                                row.put("timebandEnd", m.group(2));
                            }
                            break;
                        }
                    } catch (Exception e) {
                        // Continue
                    }
                }
            }
        }
    }

    /**
     * YOUR extractNumericFields() + applyFieldValue() logic
     */
    private void extractNumericFields(String text, List<TenantFieldDef> dataFields, 
                                     ExtractedRow row, int score) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        
        for (TenantFieldDef fieldDef : dataFields) {
            String extracted = tryExtractField(text, normalized, fieldDef);
            
            if (extracted != null) {
                Object parsed = parseValue(extracted, fieldDef.getFieldType());
                if (parsed != null) {
                    row.put(fieldDef.getFieldName(), parsed);
                    score += fieldDef.getScore();
                }
            }
        }
        
        row.score(score);
    }

    /**
     * YOUR calculateFctIfMissing() logic
     */
    private void calculateFctIfMissing(ExtractedRow row) {
        if (!row.containsKey("fct") && row.containsKey("spots") && row.containsKey("duration")) {
            try {
                int spots = ((Number) row.get("spots")).intValue();
                int duration = ((Number) row.get("duration")).intValue();
                if (spots > 0 && duration > 0) {
                    int calculatedFct = spots * duration;
                    row.put("fct", calculatedFct);
                    log.debug("Calculated FCT: {} (spots: {}, duration: {})",
                            calculatedFct, spots, duration);
                }
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    /**
     * YOUR hasMinimumData() check
     */
    private boolean hasMinimumData(ExtractedRow row) {
        return row.containsKey("amount") || 
               row.containsKey("spots") || 
               row.containsKey("rate");
    }

    /**
     * YOUR isTotalLine() logic
     */
    private boolean isTotalLine(String line) {
        String lower = line.toLowerCase();
        return TOTAL_LINE_INDICATORS.stream().anyMatch(lower::contains);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SHARED UTILITIES
    // ═══════════════════════════════════════════════════════════════════════

    private List<String> segmentText(String fullText, TenantBlockConfig config) {
        if (config.getBlockMode() == TenantBlockConfig.BlockMode.GLOBAL) {
            return List.of(fullText);
        }

        if (config.getBlockStartPattern() == null || config.getBlockStartPattern().isBlank()) {
            return List.of(fullText);
        }

        Pattern startPattern = compilePattern(config.getBlockStartPattern());
        if (startPattern == null) return List.of(fullText);

        List<String> segments = new ArrayList<>();
        String[] lines = fullText.split("\n");
        StringBuilder buffer = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (startPattern.matcher(trimmed).find()) {
                if (buffer.length() > 0) {
                    segments.add(buffer.toString().trim());
                    buffer.setLength(0);
                }
            }
            buffer.append(trimmed).append(" ");
        }

        if (buffer.length() > 0) {
            segments.add(buffer.toString().trim());
        }

        return segments;
    }

    private ExtractedRow extractRow(String blockText, List<TenantFieldDef> fieldDefs,
                                    Map<String, String> cityMappings) {
        ExtractedRow row = new ExtractedRow();
        int totalScore = 0;

        List<TenantFieldDef> sorted = fieldDefs.stream()
                .sorted(Comparator.comparingInt(TenantFieldDef::getSortOrder))
                .toList();

        String normalized = blockText.replaceAll("\\s+", " ").trim();

        for (TenantFieldDef fieldDef : sorted) {
            String extracted = tryExtractField(blockText, normalized, fieldDef);

            if (extracted != null) {
                Object parsed = parseValue(extracted, fieldDef.getFieldType());
                if (parsed != null) {
                    if ("cityName".equals(fieldDef.getFieldName()) && !cityMappings.isEmpty()) {
                        String mapped = cityMappings.get(extracted.toUpperCase().trim());
                        if (mapped != null) parsed = mapped;
                    }

                    row.put(fieldDef.getFieldName(), parsed);
                    totalScore += fieldDef.getScore();
                }
            }
        }

        row.setScore(totalScore);
        return row;
    }

    /**
     * YOUR extractFieldValue() - try each pattern in order
     */
    private String tryExtractField(String text, TenantFieldDef fieldDef) {
        for (String regex : fieldDef.getExtractionPatterns()) {
            try {
                Pattern pattern = compilePattern(regex);
                if (pattern == null) continue;
                
                Matcher matcher = pattern.matcher(text);

                if (matcher.find() && matcher.groupCount() > 0) {
                    String value = cleanNumericValue(matcher.group(1));
                    if (!value.isBlank()) {
                        return value;
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to extract field {} with pattern: {}", 
                         fieldDef.getFieldName(), regex);
            }
        }

        return null;
    }

    private String tryExtractField(String original, String normalized, TenantFieldDef fieldDef) {
        // Try original first
        String result = tryExtractField(original, fieldDef);
        if (result != null) return result;
        
        // Try normalized as fallback
        if (!original.equals(normalized)) {
            return tryExtractField(normalized, fieldDef);
        }
        
        return null;
    }

    private Object parseValue(String raw, TenantFieldDef.FieldType type) {
        if (raw == null || raw.isBlank()) return null;
        
        try {
            return switch (type) {
                case STRING -> raw;
                case INTEGER -> {
                    String cleaned = raw.replaceAll("[^0-9]", "");
                    if (cleaned.isEmpty()) yield null;
                    yield Integer.parseInt(cleaned);
                }
                case DOUBLE -> {
                    String cleaned = raw.replaceAll("[^0-9.]", "");
                    if (cleaned.isEmpty()) yield null;
                    yield Double.parseDouble(cleaned);
                }
                case DATE -> raw;
            };
        } catch (NumberFormatException e) {
            log.debug("Failed to parse '{}' as {}", raw, type);
            return null;
        }
    }

    private boolean passesRequiredCheck(ExtractedRow row, List<TenantFieldDef> fieldDefs) {
        for (TenantFieldDef def : fieldDefs) {
            if (def.isRequired() && !row.containsKey(def.getFieldName())) {
                return false;
            }
        }
        return true;
    }

    private Pattern compilePattern(String regex) {
        if (regex == null || regex.isBlank()) return null;
        
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            log.warn("Invalid regex pattern: {}", regex);
            return null;
        }
    }

    private String cleanNumericValue(String value) {
        return value.replaceAll("[,\\s]", "").trim();
    }

    private String cleanDateValue(String value) {
        return value.replaceAll("\\s+", "").trim();
    }
}