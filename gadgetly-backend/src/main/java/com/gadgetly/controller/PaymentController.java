package com.gadgetly.controller;

import com.gadgetly.dto.PaymentInitiateRequest;
import com.gadgetly.dto.PaymentResponse;
import com.gadgetly.dto.PaymentVerifyRequest;
import com.gadgetly.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/initiate")
    public ResponseEntity<PaymentResponse> initiate(@Valid @RequestBody PaymentInitiateRequest req) {
        return ResponseEntity.ok(paymentService.initiate(req));
    }

    @PostMapping("/verify")
    public ResponseEntity<PaymentResponse> verify(@Valid @RequestBody PaymentVerifyRequest req) {
        return ResponseEntity.ok(paymentService.verify(req.paymentId(), req.providerReference()));
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<PaymentResponse> status(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.getStatus(id));
    }
}
