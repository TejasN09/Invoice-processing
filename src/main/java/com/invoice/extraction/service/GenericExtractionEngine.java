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
 * IMPROVED UNIFIED EXTRACTION ENGINE
 * 
 * Key Improvements:
 * 1. Better line normalization and preprocessing
 * 2. Improved context field detection
 * 3. Smarter row boundary detection
 * 4. Better handling of multi-line rows
 * 5. Enhanced pattern matching with fallback strategies
 */
@Service
@Slf4j
public class GenericExtractionEngine {

    private final TenantConfigService configService;

    public GenericExtractionEngine(TenantConfigService configService) {
        this.configService = configService;
    }

    /**
     * Main entry point - works for ALL invoice types
     */
    public ExtractionResult extract(String pdfText, InvoiceTenant tenant) {
        ExtractionResult result = new ExtractionResult();
        result.setTenantKey(tenant.getTenantKey());
        result.setTenantName(tenant.getDisplayName());

        // Preprocess text for better extraction
        String preprocessed = preprocessText(pdfText);
        
        // Group configurations
        Map<String, List<TenantFieldDef>> fieldsByBlock = tenant.getFieldDefs().stream()
                .collect(Collectors.groupingBy(TenantFieldDef::getBlockName));
        
        Map<String, TenantBlockConfig> configByBlock = tenant.getBlockConfigs().stream()
                .collect(Collectors.toMap(TenantBlockConfig::getBlockName, c -> c));
        
        Map<String, String> cityMappings = configService.getCityMappings(tenant.getId());

        // Process each block
        for (Map.Entry<String, TenantBlockConfig> entry : configByBlock.entrySet()) {
            String blockName = entry.getKey();
            TenantBlockConfig blockConfig = entry.getValue();
            List<TenantFieldDef> fieldDefs = fieldsByBlock.getOrDefault(blockName, List.of());

            if (fieldDefs.isEmpty()) continue;

            boolean hasContextFields = fieldDefs.stream().anyMatch(TenantFieldDef::isContext);

            List<ExtractedRow> rows = hasContextFields
                    ? extractWithContext(preprocessed, blockConfig, fieldDefs, cityMappings)
                    : extractSimple(preprocessed, blockConfig, fieldDefs, cityMappings);

            result.getBlockResults().put(blockName, rows);
            
            log.info("Block '{}' extracted {} rows (hasContext={})", blockName, rows.size(), hasContextFields);
        }

        result.calculateAccuracy();
        return result;
    }

    /**
     * Preprocess PDF text to normalize spacing and handle special characters
     */
    private String preprocessText(String text) {
        if (text == null) return "";
        
        // Normalize line breaks
        text = text.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
        
        // Remove zero-width spaces and other invisible characters
        text = text.replaceAll("[\\u200B-\\u200D\\uFEFF]", "");
        
        // Normalize multiple spaces (but preserve structure)
        // DON'T collapse all spaces - we need alignment for some formats
        text = text.replaceAll("[ \\t]+", " ");
        
        // Remove completely empty lines but preserve structure
        text = text.replaceAll("\\n\\s*\\n\\s*\\n", "\n\n");
        
        return text;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SIMPLE EXTRACTION (TV, Income Tax - no context)
    // ═══════════════════════════════════════════════════════════════════════

    private List<ExtractedRow> extractSimple(
            String pdfText,
            TenantBlockConfig blockConfig,
            List<TenantFieldDef> fieldDefs,
            Map<String, String> cityMappings) {

        List<String> textBlocks = segmentTextImproved(pdfText, blockConfig);
        List<ExtractedRow> rows = new ArrayList<>();

        log.debug("Simple extraction: {} blocks to process", textBlocks.size());

        for (String block : textBlocks) {
            ExtractedRow row = extractRow(block, fieldDefs, cityMappings);
            int score = row.getScore();

            if (score >= blockConfig.getMinScore() && passesRequiredCheck(row, fieldDefs)) {
                rows.add(row);
                log.trace("Extracted row with score {}: {}", score, row);
            }
        }

        return rows;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONTEXT-AWARE EXTRACTION (Radio - hierarchical structure)
    // ═══════════════════════════════════════════════════════════════════════

    private List<ExtractedRow> extractWithContext(
            String pdfText,
            TenantBlockConfig blockConfig,
            List<TenantFieldDef> allFieldDefs,
            Map<String, String> cityMappings) {

        // Separate context from data fields
        List<TenantFieldDef> contextFields = allFieldDefs.stream()
                .filter(TenantFieldDef::isContext)
                .sorted(Comparator.comparingInt(TenantFieldDef::getSortOrder))
                .toList();

        List<TenantFieldDef> dataFields = allFieldDefs.stream()
                .filter(f -> !f.isContext())
                .sorted(Comparator.comparingInt(TenantFieldDef::getSortOrder))
                .toList();

        log.debug("Context extraction: {} context fields, {} data fields", 
                contextFields.size(), dataFields.size());

        // State tracking
        Map<String, Object> currentContext = new LinkedHashMap<>();
        List<ExtractedRow> rows = new ArrayList<>();
        
        // Multi-line row buffer
        StringBuilder rowBuffer = new StringBuilder();
        int rowStartLineNum = -1;
        
        // Compile patterns once
        Pattern rowStartPattern = compilePattern(blockConfig.getBlockStartPattern());
        
        String[] lines = pdfText.split("\n");
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            
            if (trimmed.isEmpty()) continue;
            
            // === STEP 1: Check for context field updates ===
            boolean isContextLine = false;
            for (TenantFieldDef contextField : contextFields) {
                String extracted = tryExtractField(line, contextField);
                
                if (extracted != null) {
                    // Apply city mapping if needed
                    if ("cityName".equals(contextField.getFieldName()) && !cityMappings.isEmpty()) {
                        String mapped = cityMappings.get(extracted.toUpperCase().trim());
                        if (mapped != null) extracted = mapped;
                    }
                    
                    // Context reset: new context scope detected
                    if (contextField.isContextResetOnMatch() && rowBuffer.length() > 0) {
                        flushDataRow(rowBuffer, rows, dataFields, currentContext, 
                                blockConfig.getMinScore(), rowStartLineNum);
                        rowBuffer.setLength(0);
                        rowStartLineNum = -1;
                    }
                    
                    currentContext.put(contextField.getFieldName(), extracted);
                    isContextLine = true;
                    
                    log.trace("Line {}: Context updated: {}={}", i+1, contextField.getFieldName(), extracted);
                    break; // Don't check other context fields
                }
            }
            
            if (isContextLine) {
                continue; // Skip to next line
            }
            
            // === STEP 2: Detect data row boundaries ===
            boolean isRowStart = false;
            
            if (rowStartPattern != null) {
                isRowStart = rowStartPattern.matcher(trimmed).find();
                
                if (isRowStart) {
                    log.trace("Line {}: Row start detected: {}", i+1, trimmed.substring(0, Math.min(50, trimmed.length())));
                }
            }
            
            // Flush previous row if new row starts
            if (isRowStart && rowBuffer.length() > 0) {
                flushDataRow(rowBuffer, rows, dataFields, currentContext, 
                        blockConfig.getMinScore(), rowStartLineNum);
                rowBuffer.setLength(0);
                rowStartLineNum = -1;
            }
            
            // === STEP 3: Accumulate data row lines ===
            
            // Start accumulating if:
            // - No pattern (accumulate everything)
            // - Pattern matched (row start)
            // - Already accumulating (continuation)
            boolean shouldAccumulate = rowStartPattern == null || isRowStart || rowBuffer.length() > 0;
            
            if (shouldAccumulate) {
                if (rowStartLineNum == -1) {
                    rowStartLineNum = i + 1;
                }
                
                // Smart line joining - preserve important spacing
                if (rowBuffer.length() > 0) {
                    // Check if we need a space or if the line continues naturally
                    char lastChar = rowBuffer.charAt(rowBuffer.length() - 1);
                    char firstChar = trimmed.charAt(0);
                    
                    // Add space if both are alphanumeric or if last ends without delimiter
                    if (Character.isLetterOrDigit(lastChar) && Character.isLetterOrDigit(firstChar)) {
                        rowBuffer.append(" ");
                    } else if (lastChar != ' ' && firstChar != ' ') {
                        rowBuffer.append(" ");
                    }
                }
                
                rowBuffer.append(trimmed);
                log.trace("Line {}: Accumulated to buffer (len={})", i+1, rowBuffer.length());
            }
        }
        
        // Flush final row
        if (rowBuffer.length() > 0) {
            flushDataRow(rowBuffer, rows, dataFields, currentContext, 
                    blockConfig.getMinScore(), rowStartLineNum);
        }
        
        log.debug("Context extraction complete: {} rows extracted", rows.size());
        return rows;
    }

    /**
     * Flush accumulated buffer as a data row
     */
    private void flushDataRow(
            StringBuilder buffer,
            List<ExtractedRow> rows,
            List<TenantFieldDef> dataFields,
            Map<String, Object> context,
            int minScore,
            int lineNum) {

        if (buffer.length() == 0) return;

        String text = buffer.toString().trim();
        
        // Skip obvious non-data lines
        if (text.length() < 3 || isHeaderLine(text) || isFooterLine(text)) {
            log.trace("Line {}: Skipped non-data line: {}", lineNum, 
                    text.substring(0, Math.min(40, text.length())));
            return;
        }

        // Extract fields
        ExtractedRow row = new ExtractedRow();
        int score = 0;
        
        // Normalize for pattern matching
        String normalized = text.replaceAll("\\s+", " ").trim();
        
        for (TenantFieldDef fieldDef : dataFields) {
            String extracted = tryExtractField(text, normalized, fieldDef);
            
            if (extracted != null) {
                Object parsed = parseValue(extracted, fieldDef.getFieldType());
                if (parsed != null) {
                    row.put(fieldDef.getFieldName(), parsed);
                    score += fieldDef.getScore();
                    log.trace("Line {}: Extracted {}={} (score +{})", 
                            lineNum, fieldDef.getFieldName(), parsed, fieldDef.getScore());
                }
            }
        }
        
        // Derive FCT if missing (common calculation)
        if (!row.containsKey("fct") && row.containsKey("spots") && row.containsKey("duration")) {
            try {
                int spots = ((Number) row.get("spots")).intValue();
                int duration = ((Number) row.get("duration")).intValue();
                if (spots > 0 && duration > 0) {
                    row.put("fct", spots * duration);
                    log.trace("Line {}: Calculated FCT = {} * {} = {}", lineNum, spots, duration, spots * duration);
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        
        row.score(score);
        
        // Merge context
        row.putAll(context);
        
        // Only keep rows with actual data
        boolean hasData = row.size() > context.size() || row.containsKey("amount");
        
        if (hasData && score >= minScore) {
            rows.add(row);
            log.debug("Line {}: Extracted row with score {}: {}", lineNum, score, row);
        } else {
            log.trace("Line {}: Skipped low-score row (score={}, min={}): {}", 
                    lineNum, score, minScore, text.substring(0, Math.min(60, text.length())));
        }
    }

    private boolean isHeaderLine(String text) {
        String lower = text.toLowerCase();
        
        // Common header patterns
        if (lower.matches(".*\\b(invoice|date|time|serial|s\\.?no|sr\\.?no|description)\\b.*" +
                "\\b(rate|amount|spots|duration|programme)\\b.*")) {
            return true;
        }
        
        // Table headers
        if (lower.matches("^(date|sr\\s*no|time|programme|description|rate|amount|spots|duration).*") &&
                text.length() < 80) {
            return true;
        }
        
        return false;
    }

    private boolean isFooterLine(String text) {
        String lower = text.toLowerCase();
        
        // Footers usually have pagination or disclaimers
        if (lower.matches(".*\\bpage\\s+\\d+\\s+of\\s+\\d+.*")) {
            return true;
        }
        
        if (lower.matches("^(this|computer|system|generated|authorized|signatory).*") &&
                text.length() < 100) {
            return true;
        }
        
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SHARED UTILITIES
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Improved text segmentation with better line grouping
     */
    private List<String> segmentTextImproved(String fullText, TenantBlockConfig config) {
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
        
        int linesInCurrentBlock = 0;
        final int MAX_LINES_PER_BLOCK = 50; // Safety limit

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            boolean isBlockStart = startPattern.matcher(trimmed).find();
            
            if (isBlockStart) {
                // Flush previous block
                if (buffer.length() > 0) {
                    segments.add(buffer.toString().trim());
                    buffer.setLength(0);
                    linesInCurrentBlock = 0;
                }
            }
            
            // Add line to buffer (whether it's a start or continuation)
            if (buffer.length() > 0) {
                buffer.append(" ");
            }
            buffer.append(trimmed);
            linesInCurrentBlock++;
            
            // Safety: flush if block gets too large
            if (linesInCurrentBlock >= MAX_LINES_PER_BLOCK && !isBlockStart) {
                segments.add(buffer.toString().trim());
                buffer.setLength(0);
                linesInCurrentBlock = 0;
            }
        }

        // Flush final block
        if (buffer.length() > 0) {
            segments.add(buffer.toString().trim());
        }

        log.debug("Segmented text into {} blocks using pattern: {}", 
                segments.size(), config.getBlockStartPattern());
        
        return segments;
    }

    /**
     * Extract fields from a single block
     */
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
                    // Apply city mapping
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
     * Try to extract a field using all patterns with fallback strategies
     */
    private String tryExtractField(String text, TenantFieldDef fieldDef) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return tryExtractField(text, normalized, fieldDef);
    }

    private String tryExtractField(String original, String normalized, TenantFieldDef fieldDef) {
        for (String regex : fieldDef.getExtractionPatterns()) {
            try {
                // Try with MULTILINE flag for patterns that span lines
                Pattern p = Pattern.compile(regex, 
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE);

                // Try both original and normalized text
                for (String candidate : List.of(original, normalized)) {
                    Matcher m = p.matcher(candidate);
                    if (m.find()) {
                        // Get first capturing group
                        if (m.groupCount() > 0) {
                            String value = m.group(1).trim();
                            // Clean up common artifacts
                            value = value.replaceAll(",", ""); // Remove commas from numbers
                            value = value.replaceAll("\\s+", " ").trim(); // Normalize spaces
                            
                            if (!value.isBlank()) {
                                return value;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Invalid regex for field '{}': {} - {}", 
                        fieldDef.getFieldName(), regex, e.getMessage());
            }
        }
        
        return null;
    }

    /**
     * Parse extracted string to appropriate type
     */
    private Object parseValue(String raw, TenantFieldDef.FieldType type) {
        if (raw == null || raw.isBlank()) return null;
        
        try {
            return switch (type) {
                case STRING -> raw;
                case INTEGER -> {
                    String cleaned = raw.replaceAll("[^0-9]", "");
                    yield cleaned.isEmpty() ? null : Integer.parseInt(cleaned);
                }
                case DOUBLE -> {
                    String cleaned = raw.replaceAll("[^0-9.]", "");
                    yield cleaned.isEmpty() ? null : Double.parseDouble(cleaned);
                }
                case DATE -> raw; // Keep as string for now
            };
        } catch (NumberFormatException e) {
            log.debug("Failed to parse '{}' as {}: {}", raw, type, e.getMessage());
            return null;
        }
    }

    /**
     * Check if row has all required fields
     */
    private boolean passesRequiredCheck(ExtractedRow row, List<TenantFieldDef> fieldDefs) {
        for (TenantFieldDef def : fieldDefs) {
            if (def.isRequired() && !row.containsKey(def.getFieldName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compile regex pattern with error handling
     */
    private Pattern compilePattern(String regex) {
        if (regex == null || regex.isBlank()) return null;
        
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        } catch (Exception e) {
            log.warn("Invalid regex pattern: {} - {}", regex, e.getMessage());
            return null;
        }
    }
}