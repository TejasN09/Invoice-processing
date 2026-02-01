package com.invoice.extraction.service;

import com.invoice.extraction.entity.*;
import com.invoice.extraction.model.ExtractionResult;
import com.invoice.extraction.model.ExtractedRow;
import com.invoice.extraction.repository.TenantRadioConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Handles the Radio-specific extraction flow.
 *
 * Radio invoices differ from TV in one critical way: rows are grouped by CITY.
 * The text looks like:
 *
 *     MUMBAI (MIRCHI 98.3 FM)
 *     07:00-11:00   30   24   720   73.10   3,289.50
 *     18:00-23:00   30   10   300   45.00   1,350.00
 *     DELHI (MIRCHI 98.3 FM)
 *     07:00-11:00   30   15   450   82.50   3,712.50
 *
 * So the engine must:
 *   1. Track "current city" as it scans lines top-to-bottom.
 *   2. When it sees a city line, flush the previous city's rows, switch context.
 *   3. Extract date range and timeband from the row using the radio-specific patterns.
 *   4. Extract the generic numeric fields (spots, fct, rate, amount) via tenant_field_defs.
 *
 * The generic fields are the SAME mechanism as TV — they're just regex patterns
 * stored in tenant_field_defs with block_name = 'invoice'.  What's different is
 * the city/date/time scaffolding around them, which lives in tenant_radio_configs.
 */
@Service
@Slf4j
public class RadioExtractionEngine {

    private final TenantRadioConfigRepository radioConfigRepo;
    private final TenantConfigService configService;

    public RadioExtractionEngine(TenantRadioConfigRepository radioConfigRepo,
                                 TenantConfigService configService) {
        this.radioConfigRepo = radioConfigRepo;
        this.configService = configService;
    }

    // ─── MAIN ENTRY ────────────────────────────────────────────────────────

    public ExtractionResult extract(String pdfText, InvoiceTenant tenant) {
        ExtractionResult result = new ExtractionResult();
        result.setTenantKey(tenant.getTenantKey());
        result.setTenantName(tenant.getDisplayName());

        // Load radio-specific config
        TenantRadioConfig radioConfig = radioConfigRepo
                .findByTenantId(tenant.getId())
                .orElseThrow(() -> new RuntimeException(
                        "No radio config found for tenant: " + tenant.getTenantKey()));

        // Load the generic field defs (spots, fct, rate, amount, duration)
        List<TenantFieldDef> fieldDefs = tenant.getFieldDefs().stream()
                .filter(f -> "invoice".equals(f.getBlockName()))
                .sorted(Comparator.comparingInt(TenantFieldDef::getSortOrder))
                .toList();

        // Load city mappings (only Radio City has these; others get direct name from regex)
        Map<String, String> cityMappings = configService.getCityMappings(tenant.getId());

        // Determine the row-start pattern: radio config override > block config
        Pattern rowStartPattern = resolveRowStartPattern(radioConfig, tenant);

        // ─── SCAN ──────────────────────────────────────────────────────────
        List<ExtractedRow> rows = new ArrayList<>();
        String currentCity = "N/A";
        StringBuilder rowBuffer = new StringBuilder();

        String[] lines = pdfText.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 1. City detection — does this line name a city?
            String detectedCity = detectCity(trimmed, radioConfig, cityMappings);
            if (detectedCity != null) {
                // Flush whatever we've buffered under the previous city
                flushBuffer(rowBuffer, rows, fieldDefs, radioConfig, currentCity);
                currentCity = detectedCity;
                continue;   // city lines are context-only, not data rows
            }

            // 2. Row boundary detection
            if (rowStartPattern != null && rowStartPattern.matcher(trimmed).find()) {
                flushBuffer(rowBuffer, rows, fieldDefs, radioConfig, currentCity);
            }

            // 3. Accumulate into buffer
            if (rowBuffer.length() > 0 || rowStartPattern == null) {
                rowBuffer.append(trimmed).append(" ");
            } else {
                rowBuffer.append(trimmed).append(" ");
            }
        }

        // Flush the last row
        flushBuffer(rowBuffer, rows, fieldDefs, radioConfig, currentCity);

        result.getBlockResults().put("invoice", rows);
        result.calculateAccuracy();
        return result;
    }

    // ─── CITY DETECTION ────────────────────────────────────────────────────

    /**
     * Tries the city_pattern regex against the line.
     * If it matches, returns the resolved city name (either direct from group 1,
     * or looked up via cityMappings if the tenant uses codes).
     */
    private String detectCity(String line, TenantRadioConfig config,
                              Map<String, String> cityMappings) {
        if (config.getCityPattern() == null) return null;

        try {
            Pattern p = Pattern.compile(config.getCityPattern(), Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(line);
            if (m.find() && m.groupCount() > 0) {
                String raw = m.group(1).trim().toUpperCase();

                // If we have city mappings, look up the code
                if (!cityMappings.isEmpty()) {
                    String mapped = cityMappings.get(raw);
                    return mapped != null ? mapped : raw;
                }
                return raw;
            }
        } catch (Exception e) {
            log.warn("City pattern error for tenant {}: {}", config.getTenant().getTenantKey(), e.getMessage());
        }
        return null;
    }

    // ─── ROW FLUSHING ──────────────────────────────────────────────────────

    /**
     * Takes the current buffer, extracts all fields, attaches city + dates + timeband,
     * and adds the row to the result list if it has meaningful data.
     */
    private void flushBuffer(StringBuilder buffer, List<ExtractedRow> rows,
                             List<TenantFieldDef> fieldDefs, TenantRadioConfig config,
                             String cityName) {
        if (buffer.length() == 0) return;

        String text = buffer.toString().trim();
        buffer.setLength(0);

        ExtractedRow row = new ExtractedRow();
        int score = 0;

        // ── Attach city ─────────────────────────────────────────────────
        row.put("cityName", cityName);

        // ── Extract dates ───────────────────────────────────────────────
        if (config.getDatePattern() != null) {
            score += extractDateFields(text, config, row);
        }

        // ── Extract timeband ────────────────────────────────────────────
        if (config.getTimePattern() != null) {
            score += extractTimebandFields(text, config, row);
        }

        // ── Extract generic numeric fields (from tenant_field_defs) ─────
        score += extractGenericFields(text, fieldDefs, row);

        row.score(score);

        // Only keep rows that have at least one numeric field extracted
        if (row.size() > 1) {   // > 1 because cityName is always there
            rows.add(row);
        } else {
            log.debug("Skipped empty row: {}", text.substring(0, Math.min(60, text.length())));
        }
    }

    // ─── DATE EXTRACTION ───────────────────────────────────────────────────

    private int extractDateFields(String text, TenantRadioConfig config, ExtractedRow row) {
        try {
            Pattern p = Pattern.compile(config.getDatePattern(), Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(text);
            if (m.find()) {
                row.put("startDate", m.group(1).trim());
                if (m.groupCount() >= 2 && m.group(2) != null) {
                    row.put("endDate", m.group(2).trim());
                }
                return 2;
            }
        } catch (Exception e) {
            log.debug("Date extraction failed: {}", e.getMessage());
        }
        return 0;
    }

    // ─── TIMEBAND EXTRACTION ───────────────────────────────────────────────

    private int extractTimebandFields(String text, TenantRadioConfig config, ExtractedRow row) {
        try {
            Pattern p = Pattern.compile(config.getTimePattern(), Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(text);
            if (m.find()) {
                if (m.groupCount() >= 1 && m.group(1) != null) {
                    row.put("timebandStart", m.group(1).trim());
                }
                if (m.groupCount() >= 2 && m.group(2) != null) {
                    row.put("timebandEnd", m.group(2).trim());
                }
                return 1;
            }
        } catch (Exception e) {
            log.debug("Timeband extraction failed: {}", e.getMessage());
        }
        return 0;
    }

    // ─── GENERIC FIELD EXTRACTION ──────────────────────────────────────────
    // Same logic as GenericExtractionEngine — shared pattern: try each regex in order.

    private int extractGenericFields(String text, List<TenantFieldDef> fieldDefs, ExtractedRow row) {
        int score = 0;
        String normalized = text.replaceAll("\\s+", " ").trim();

        for (TenantFieldDef def : fieldDefs) {
            String extracted = tryExtract(text, normalized, def);
            if (extracted != null) {
                Object parsed = parseValue(extracted, def.getFieldType());
                if (parsed != null) {
                    row.put(def.getFieldName(), parsed);
                    score += def.getScore();
                }
            }
        }

        // ── Derived field: calculate FCT if missing ─────────────────────
        if (!row.containsKey("fct") && row.containsKey("spots") && row.containsKey("duration")) {
            try {
                int spots = ((Number) row.get("spots")).intValue();
                int duration = ((Number) row.get("duration")).intValue();
                if (spots > 0 && duration > 0) {
                    row.put("fct", spots * duration);
                    log.debug("Calculated FCT = {} (spots={}, duration={})", spots * duration, spots, duration);
                }
            } catch (Exception e) {
                // silently skip
            }
        }

        return score;
    }

    private String tryExtract(String original, String normalized, TenantFieldDef def) {
        for (String regex : def.getExtractionPatterns()) {
            try {
                Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                for (String candidate : List.of(original, normalized)) {
                    Matcher m = p.matcher(candidate);
                    if (m.find() && m.groupCount() > 0) {
                        String value = m.group(1).trim().replace(",", "");
                        if (!value.isBlank()) return value;
                    }
                }
            } catch (Exception e) {
                log.warn("Regex error for field \'{}\': {}", def.getFieldName(), regex);
            }
        }
        return null;
    }

    private Object parseValue(String raw, TenantFieldDef.FieldType type) {
        try {
            return switch (type) {
                case STRING  -> raw;
                case INTEGER -> Integer.parseInt(raw.replaceAll("[^0-9]", ""));
                case DOUBLE  -> Double.parseDouble(raw.replaceAll("[^0-9.]", ""));
                case DATE    -> raw;
            };
        } catch (NumberFormatException e) {
            log.debug("Parse failed: \'{}\' as {}", raw, type);
            return null;
        }
    }

    // ─── ROW START RESOLUTION ──────────────────────────────────────────────

    /**
     * Radio config can override the block-level row_start_pattern.
     * Priority: radioConfig.rowStartPattern > blockConfig.blockStartPattern > null (every line is a row)
     */
    private Pattern resolveRowStartPattern(TenantRadioConfig radioConfig, InvoiceTenant tenant) {
        // 1. Radio config override
        if (radioConfig.getRowStartPattern() != null) {
            return Pattern.compile(radioConfig.getRowStartPattern(), Pattern.CASE_INSENSITIVE);
        }

        // 2. Block config from tenant_block_configs where block_name = 'invoice'
        return tenant.getBlockConfigs().stream()
                .filter(bc -> "invoice".equals(bc.getBlockName()))
                .filter(bc -> bc.getBlockStartPattern() != null)
                .findFirst()
                .map(bc -> Pattern.compile(bc.getBlockStartPattern(), Pattern.CASE_INSENSITIVE))
                .orElse(null);
    }
}