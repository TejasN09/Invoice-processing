package com.invoice.extraction.controller;

import com.invoice.extraction.model.ExtractionResult;
import com.invoice.extraction.service.InvoiceExtractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/invoices")
@Slf4j
public class InvoiceExtractionController {

    private final InvoiceExtractionService extractionService;

    public InvoiceExtractionController(InvoiceExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    /**
     * Single endpoint. Upload any supported invoice PDF.
     * The system automatically identifies the type and extracts accordingly.
     */
    @PostMapping("/extract")
    public ResponseEntity<ExtractionResult> extract(@RequestParam("file") MultipartFile file) {
        try {
            ExtractionResult result = extractionService.extract(file);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Extraction failed", e);
            ExtractionResult error = new ExtractionResult();
            error.setStatus("ERROR");
            error.getWarnings().add(e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}