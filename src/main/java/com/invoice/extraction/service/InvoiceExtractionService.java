package com.invoice.extraction.service;

import com.invoice.extraction.entity.*;
import com.invoice.extraction.model.ExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * SIMPLIFIED Top-level orchestrator - TRUE MULTI-TENANT DESIGN
 *
 * ═══════════════════════════════════════════════════════════════════════
 * BEFORE (Complex, Non-Scalable):
 * ═══════════════════════════════════════════════════════════════════════
 * - TV invoices → GenericExtractionEngine
 * - Radio invoices → RadioExtractionEngine (special case!)
 * - Income Tax → Need another special engine?
 * - New format → Need yet another engine?
 *
 * ═══════════════════════════════════════════════════════════════════════
 * AFTER (Simple, Truly Multi-Tenant):
 * ═══════════════════════════════════════════════════════════════════════
 * - ALL invoices → GenericExtractionEngine
 * - ALL behavior → Database configuration
 * - NO special logic for any format
 * - Add new format → Just add database config
 *
 * The engine is "generic" because it handles both:
 * 1. Flat invoices (TV): Each row is independent
 * 2. Hierarchical invoices (Radio): Rows inherit context
 *
 * The difference is purely configuration (is_context field).
 */
@Service
@Slf4j
public class InvoiceExtractionService {

    private final TenantIdentificationService identificationService;
    private final GenericExtractionEngine engine;

    public InvoiceExtractionService(
            TenantIdentificationService identificationService,
            GenericExtractionEngine engine) {

        this.identificationService = identificationService;
        this.engine = engine;
    }

    /**
     * Single extraction method for ALL invoice types.
     * No routing logic. No special cases. Just extract.
     */
    public ExtractionResult extract(MultipartFile file) throws Exception {
        // 1. Extract text from PDF
        String pdfText = extractText(file);

        if (pdfText == null || pdfText.isBlank()) {
            return ExtractionResult.empty("PDF contains no extractable text");
        }

        // 2. Identify tenant (TV, Radio, Income Tax, etc.)
        InvoiceTenant tenant = identificationService.identifyTenant(pdfText)
                .orElseThrow(() -> new RuntimeException(
                        "Could not identify invoice type. No tenant matched."));

        log.info("Identified tenant: {} for file: {}", tenant.getTenantKey(), file.getOriginalFilename());

        // 3. Extract using the unified engine
        //    The engine automatically handles context fields if the tenant has them
        ExtractionResult result = engine.extract(pdfText, tenant);

        log.info("Extraction completed: {} blocks extracted, accuracy: {}%",
                result.getBlockResults().size(), String.format("%.1f", result.getAccuracy()));

        return result;
    }

    /**
     * Extract text from PDF using PDFBox
     */
    private String extractText(MultipartFile file) throws Exception {
        try (PDDocument doc = Loader.loadPDF(
                new RandomAccessReadBuffer(file.getInputStream()))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }
}