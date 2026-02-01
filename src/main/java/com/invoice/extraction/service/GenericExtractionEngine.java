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

@Service
@Slf4j
public class GenericExtractionEngine {

    private final TenantConfigService configService;

    public GenericExtractionEngine(TenantConfigService configService) {
        this.configService = configService;
    }

    /**
     * Main entry point. Takes the full PDF text and a resolved tenant,
     * and returns a fully populated ExtractionResult.
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

        // City mappings (if any)
        Map<String, String> cityMappings = configService.getCityMappings(tenant.getId());

        // Process each block type
        for (Map.Entry<String, TenantBlockConfig> entry : configByBlock.entrySet()) {
            String blockName = entry.getKey();
            TenantBlockConfig blockConfig = entry.getValue();
            List<TenantFieldDef> fieldDefs = fieldsByBlock.getOrDefault(blockName, List.of());

            if (fieldDefs.isEmpty()) continue;

            List<String> textBlocks = segmentText(pdfText, blockConfig);
            List<ExtractedRow> rows = new ArrayList<>();

            for (String block : textBlocks) {
                ExtractedRow row = extractRow(block, fieldDefs, cityMappings);
                int score = row.getScore();

                if (score >= blockConfig.getMinScore() && passesRequiredCheck(row, fieldDefs)) {
                    rows.add(row);
                }
            }

            result.getBlockResults().put(blockName, rows);
        }

        // Handle QR-based summary if enabled
        // (QR logic stays separate — see QrExtractionService)

        result.calculateAccuracy();
        return result;
    }

    // ─── TEXT SEGMENTATION ─────────────────────────────────────────────

    /**
     * Splits the full PDF text into segments based on the block config.
     * GLOBAL mode = entire text is one block.
     * LINE_SPLIT mode = split on the block_start_pattern regex.
     */
    private List<String> segmentText(String fullText, TenantBlockConfig config) {
        if (config.getBlockMode() == TenantBlockConfig.BlockMode.GLOBAL) {
            return List.of(fullText);
        }

        // LINE_SPLIT: chunk text wherever block_start_pattern matches
        List<String> segments = new ArrayList<>();
        if (config.getBlockStartPattern() == null) {
            return List.of(fullText);
        }

        Pattern startPattern = Pattern.compile(
                config.getBlockStartPattern(), Pattern.CASE_INSENSITIVE);

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

    // ─── ROW EXTRACTION ────────────────────────────────────────────────

    /**
     * Given a single text segment and a list of field definitions,
     * extracts all fields into a generic ExtractedRow (a Map<String, Object>).
     * No switch statements. No hardcoded field names.
     */
    private ExtractedRow extractRow(String blockText, List<TenantFieldDef> fieldDefs,
                                    Map<String, String> cityMappings) {
        ExtractedRow row = new ExtractedRow();
        int totalScore = 0;

        // Sort by sort_order to apply extraction in the configured priority
        List<TenantFieldDef> sorted = fieldDefs.stream()
                .sorted(Comparator.comparingInt(TenantFieldDef::getSortOrder))
                .toList();

        String normalized = blockText.replaceAll("\\s+", " ").trim();

        for (TenantFieldDef fieldDef : sorted) {
            String extracted = tryExtract(blockText, normalized, fieldDef);

            if (extracted != null) {
                Object parsed = parseValue(extracted, fieldDef.getFieldType());
                if (parsed != null) {
                    // Apply city mapping if this is a city field and mapping exists
                    if ("cityName".equals(fieldDef.getFieldName()) && !cityMappings.isEmpty()) {
                        String mapped = cityMappings.get(extracted.toUpperCase().trim());
                        if (mapped != null) {
                            parsed = mapped;
                        }
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
     * Tries each regex pattern in order until one matches.
     * Returns the first captured group, or null.
     */
    private String tryExtract(String original, String normalized, TenantFieldDef fieldDef) {
        for (String regex : fieldDef.getExtractionPatterns()) {
            try {
                Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

                // Try original text first, then normalized
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

    // ─── TYPE PARSING ──────────────────────────────────────────────────

    private Object parseValue(String raw, TenantFieldDef.FieldType type) {
        try {
            return switch (type) {
                case STRING -> raw;
                case INTEGER -> Integer.parseInt(raw.replaceAll("[^0-9]", ""));
                case DOUBLE  -> Double.parseDouble(raw.replaceAll("[^0-9.]", ""));
                case DATE    -> raw;   // Store as string; format normalization can be added later
            };
        } catch (NumberFormatException e) {
            log.debug("Failed to parse '{}' as {}", raw, type);
            return null;
        }
    }

    // ─── VALIDATION ────────────────────────────────────────────────────

    private boolean passesRequiredCheck(ExtractedRow row, List<TenantFieldDef> fieldDefs) {
        for (TenantFieldDef def : fieldDefs) {
            if (def.isRequired() && !row.containsKey(def.getFieldName())) {
                return false;
            }
        }
        return true;
    }
}