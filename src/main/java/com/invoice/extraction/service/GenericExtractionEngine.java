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
 * UNIFIED EXTRACTION ENGINE - Handles ALL invoice types (TV, Radio, Income Tax, etc.)
 * through database-driven configuration alone.
 *
 * KEY CONCEPT: Context Fields
 * ============================
 * Some invoices have hierarchical structure where certain fields (like "city" in radio)
 * apply to multiple data rows. These are marked as is_context=TRUE in tenant_field_defs.
 *
 * Context fields are extracted once and their values propagate to all subsequent rows
 * until a new value is detected.
 *
 * Example (Radio Invoice):
 *   MUMBAI (MIRCHI)          ← Context: city="MUMBAI"
 *   01.01.2024 31.01.2024    ← Context: dateRange="01.01-31.01"
 *   07:00-11:00  30  24  ... ← Data row (inherits city + dateRange)
 *   18:00-23:00  30  10  ... ← Data row (inherits city + dateRange)
 *   DELHI (MIRCHI)           ← Context switch: city="DELHI"
 *   ...
 *
 * This makes the engine truly multi-tenant - NO special logic for Radio or any other format.
 */
@Service
@Slf4j
public class GenericExtractionEngine {

    private final TenantConfigService configService;

    public GenericExtractionEngine(TenantConfigService configService) {
        this.configService = configService;
    }

    /**
     * Main entry point. Works for TV, Radio, Income Tax, or ANY configured tenant.
     */
    public ExtractionResult extract(String pdfText, InvoiceTenant tenant) {
        ExtractionResult result = new ExtractionResult();
        result.setTenantKey(tenant.getTenantKey());
        result.setTenantName(tenant.getDisplayName());

        // Group field defs by block name
        Map<String, List<TenantFieldDef>> fieldsByBlock = tenant.getFieldDefs().stream()
                .collect(Collectors.groupingBy(TenantFieldDef::getBlockName));

        // Group block configs by block name
        Map<String, TenantBlockConfig> configByBlock = tenant.getBlockConfigs().stream()
                .collect(Collectors.toMap(TenantBlockConfig::getBlockName, c -> c));

        // City mappings (for tenants that use codes)
        Map<String, String> cityMappings = configService.getCityMappings(tenant.getId());

        // Process each block type
        for (Map.Entry<String, TenantBlockConfig> entry : configByBlock.entrySet()) {
            String blockName = entry.getKey();
            TenantBlockConfig blockConfig = entry.getValue();
            List<TenantFieldDef> fieldDefs = fieldsByBlock.getOrDefault(blockName, List.of());

            if (fieldDefs.isEmpty()) continue;

            // Check if this block has context fields
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
    // SIMPLE EXTRACTION (TV, Income Tax - no context)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Used for flat invoices where each row is independent (TV, Income Tax).
     * This is the original logic - unchanged.
     */
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
    // CONTEXT-AWARE EXTRACTION (Radio - hierarchical structure)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Used for hierarchical invoices where some fields apply to multiple rows (Radio).
     * This replaces the entire RadioExtractionEngine with a generic, config-driven approach.
     */
    private List<ExtractedRow> extractWithContext(
            String pdfText,
            TenantBlockConfig blockConfig,
            List<TenantFieldDef> allFieldDefs,
            Map<String, String> cityMappings) {

        // Separate context fields from data fields
        List<TenantFieldDef> contextFields = allFieldDefs.stream()
                .filter(TenantFieldDef::isContext)
                .sorted(Comparator.comparingInt(TenantFieldDef::getSortOrder))
                .toList();

        List<TenantFieldDef> dataFields = allFieldDefs.stream()
                .filter(f -> !f.isContext())
                .sorted(Comparator.comparingInt(TenantFieldDef::getSortOrder))
                .toList();

        log.debug("Context-aware extraction: {} context fields, {} data fields",
                contextFields.size(), dataFields.size());

        // ── CONTEXT TRACKING ──────────────────────────────────────────
        Map<String, Object> currentContext = new LinkedHashMap<>();
        List<ExtractedRow> rows = new ArrayList<>();
        StringBuilder rowBuffer = new StringBuilder();

        // Determine row start pattern (for detecting data row boundaries)
        Pattern rowStartPattern = compilePattern(blockConfig.getBlockStartPattern());

        String[] lines = pdfText.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.isEmpty()) continue;

            // ── STEP 1: Try to extract context fields ──────────────────
            boolean isContextLine = false;
            for (TenantFieldDef contextField : contextFields) {
                String extracted = tryExtractField(trimmed, contextField);

                if (extracted != null) {
                    // Apply city mapping if applicable
                    if ("cityName".equals(contextField.getFieldName()) && !cityMappings.isEmpty()) {
                        String mapped = cityMappings.get(extracted.toUpperCase().trim());
                        if (mapped != null) extracted = mapped;
                    }

                    // Context reset: if this field creates a new scope, flush previous rows
                    if (contextField.isContextResetOnMatch() && rowBuffer.length() > 0) {
                        flushDataRow(rowBuffer, rows, dataFields, currentContext, blockConfig.getMinScore());
                    }

                    currentContext.put(contextField.getFieldName(), extracted);
                    isContextLine = true;

                    log.trace("Context updated: {}={}", contextField.getFieldName(), extracted);
                    break;  // Don't check other context fields for this line
                }
            }

            if (isContextLine) {
                continue;  // Context lines are not data rows
            }

            // ── STEP 2: Detect data row boundaries ─────────────────────
            boolean isRowStart = rowStartPattern != null && rowStartPattern.matcher(trimmed).find();

            if (isRowStart && rowBuffer.length() > 0) {
                // Flush previous row
                flushDataRow(rowBuffer, rows, dataFields, currentContext, blockConfig.getMinScore());
                rowBuffer.setLength(0);
            }

            // ── STEP 3: Accumulate data row text ───────────────────────
            if (rowStartPattern == null || rowBuffer.length() > 0 || isRowStart) {
                rowBuffer.append(trimmed).append(" ");
            }
        }

        // Flush final row
        if (rowBuffer.length() > 0) {
            flushDataRow(rowBuffer, rows, dataFields, currentContext, blockConfig.getMinScore());
        }

        log.info("Context-aware extraction completed: {} rows extracted", rows.size());
        return rows;
    }

    /**
     * Flush a data row, merging it with current context.
     */
    private void flushDataRow(
            StringBuilder buffer,
            List<ExtractedRow> rows,
            List<TenantFieldDef> dataFields,
            Map<String, Object> context,
            int minScore) {

        if (buffer.length() == 0) return;

        String text = buffer.toString().trim();

        // Skip obvious header lines
        if (text.length() < 5 || isHeaderLine(text)) {
            log.trace("Skipped header/short line: {}", text.substring(0, Math.min(40, text.length())));
            return;
        }

        // Extract data fields
        ExtractedRow row = new ExtractedRow();
        int score = 0;

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

        // Derive FCT if missing (common for radio invoices)
        if (!row.containsKey("fct") && row.containsKey("spots") && row.containsKey("duration")) {
            try {
                int spots = ((Number) row.get("spots")).intValue();
                int duration = ((Number) row.get("duration")).intValue();
                if (spots > 0 && duration > 0) {
                    row.put("fct", spots * duration);
                    log.trace("Calculated FCT = {}", spots * duration);
                }
            } catch (Exception e) {
                // Silently skip
            }
        }

        row.score(score);

        // Merge context into row
        row.putAll(context);

        // Only keep rows with meaningful data
        boolean hasData = row.size() > context.size() || row.containsKey("amount");

        if (hasData && score >= minScore) {
            rows.add(row);
            log.debug("Extracted row with score {}: {}", score, row);
        } else {
            log.trace("Skipped low-score row (score={}): {}", score,
                    text.substring(0, Math.min(60, text.length())));
        }
    }

    private boolean isHeaderLine(String text) {
        String lower = text.toLowerCase();
        return lower.contains("invoice") && lower.contains("number") ||
                lower.contains("date") && lower.contains("time") && text.length() < 50 ||
                lower.contains("total") && lower.contains("amount") && text.length() < 50;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SHARED UTILITIES (used by both simple and context-aware extraction)
    // ═══════════════════════════════════════════════════════════════════════

    private List<String> segmentText(String fullText, TenantBlockConfig config) {
        if (config.getBlockMode() == TenantBlockConfig.BlockMode.GLOBAL) {
            return List.of(fullText);
        }

        List<String> segments = new ArrayList<>();
        if (config.getBlockStartPattern() == null || config.getBlockStartPattern().isBlank()) {
            return List.of(fullText);
        }

        Pattern startPattern = compilePattern(config.getBlockStartPattern());
        if (startPattern == null) return List.of(fullText);

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

    private String tryExtractField(String text, TenantFieldDef fieldDef) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return tryExtractField(text, normalized, fieldDef);
    }

    private String tryExtractField(String original, String normalized, TenantFieldDef fieldDef) {
        for (String regex : fieldDef.getExtractionPatterns()) {
            try {
                Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

                for (String candidate : List.of(original, normalized)) {
                    Matcher m = p.matcher(candidate);
                    if (m.find() && m.groupCount() > 0) {
                        String value = m.group(1).trim().replace(",", "");
                        if (!value.isBlank()) {
                            return value;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Invalid regex for field '{}': {}", fieldDef.getFieldName(), regex);
            }
        }
        return null;
    }

    private Object parseValue(String raw, TenantFieldDef.FieldType type) {
        try {
            return switch (type) {
                case STRING -> raw;
                case INTEGER -> Integer.parseInt(raw.replaceAll("[^0-9]", ""));
                case DOUBLE -> Double.parseDouble(raw.replaceAll("[^0-9.]", ""));
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
}