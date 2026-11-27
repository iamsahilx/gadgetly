package com.gadgetly.service;

import com.gadgetly.dto.PaymentInitiateRequest;
import com.gadgetly.dto.PaymentResponse;
import com.gadgetly.entity.Order;
import com.gadgetly.entity.Payment;
import com.gadgetly.exception.ApiException;
import com.gadgetly.repository.PaymentRepository;
import com.gadgetly.service.payment.PaymentGatewayService;
import com.gadgetly.service.payment.PaymentProviderResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Business-logic layer for payments. This is the ONLY class (besides controllers)
 * that touches payment concerns, and it depends purely on PaymentGatewayService —
 * never on DummyPaymentGatewayProvider or any other concrete provider directly.
 * Swapping providers never requires touching this class.
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentProviderResolver providerResolver;
    private final OrderService orderService;

    @Transactional
    public PaymentResponse initiate(Long userId, PaymentInitiateRequest req) {
        Order order = orderService.getEntity(req.orderId());
        requireOwner(order, userId);
        if (order.getStatus() != Order.Status.PENDING) {
            throw ApiException.badRequest("Order is not awaiting payment");
        }

        // An order has one payment record. A declined attempt may be retried using that
        // record, while an initiated attempt must not create another gateway charge.
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElse(null);
        if (payment != null && payment.getStatus() == Payment.Status.INITIATED) {
            return toResponse(payment, payment.getProvider(), "Payment already initiated");
        }
        if (payment != null && payment.getStatus() != Payment.Status.FAILED) {
            throw ApiException.conflict("Payment cannot be retried");
        }

        PaymentGatewayService gateway = providerResolver.resolve();

        var command = new PaymentGatewayService.InitiateCommand(
                order.getId(), order.getTotal(), req.method(),
                req.cardNumber(), req.cardExpiry(), req.cardCvv(), req.upiId(), req.bankCode());

        var result = gateway.initiate(command);

        if (payment == null) payment = new Payment();
        payment.setOrder(order);
        payment.setMethod(Payment.Method.valueOf(req.method().toUpperCase()));
        payment.setAmount(order.getTotal());
        payment.setProvider(gateway.providerName());
        payment.setProviderReference(result.providerReference());
        payment.setStatus(mapStatus(result.status()));
        payment.setFailureReason(result.status() == PaymentGatewayService.GatewayStatus.FAILED
                ? result.message() : null);
        payment = paymentRepository.save(payment);

        if (result.status() == PaymentGatewayService.GatewayStatus.SUCCESS
                || (payment.getMethod() == Payment.Method.COD
                        && result.status() == PaymentGatewayService.GatewayStatus.INITIATED)) {
            orderService.markConfirmedAndDecrementStock(order.getId());
        }
        // Failed attempts leave the order PENDING so the user can retry the same order.
        // Stock remains reserved until success or explicit cancellation. Accepted COD
        // orders are confirmed even though payment is due on delivery.

        return toResponse(payment, gateway.providerName(), result.message());
    }

    @Transactional
    public PaymentResponse verify(Long userId, Long paymentId, String providerReference) {
        // Lock the order before loading its payment, matching initiate/cancel lock order.
        // This prevents two verifications or a cancellation from racing each other.
        Long orderId = paymentRepository.findOrderIdById(paymentId)
                .orElseThrow(() -> ApiException.notFound("Payment not found"));
        Order order = orderService.getEntity(orderId);
        requireOwner(order, userId);
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("Payment not found"));
        if (order.getStatus() == Order.Status.CANCELLED) {
            throw ApiException.conflict("Cancelled order cannot be paid");
        }
        if (!Objects.equals(payment.getProviderReference(), providerReference)) {
            throw ApiException.badRequest("Payment reference does not match this payment");
        }
        if (payment.getStatus() != Payment.Status.INITIATED) {
            return toResponse(payment, payment.getProvider(), "Payment already resolved");
        }

        PaymentGatewayService gateway = providerResolver.resolve();
        if (!gateway.providerName().equals(payment.getProvider())) {
            throw ApiException.conflict("Payment provider is unavailable");
        }
        var result = gateway.verify(payment.getProviderReference());
        if (!Objects.equals(payment.getProviderReference(), result.providerReference())) {
            throw ApiException.conflict("Payment provider returned a different reference");
        }

        payment.setStatus(mapStatus(result.status()));
        payment.setFailureReason(result.status() == PaymentGatewayService.GatewayStatus.FAILED
                ? result.message() : null);
        payment = paymentRepository.save(payment);

        if (result.status() == PaymentGatewayService.GatewayStatus.SUCCESS) {
            orderService.markConfirmedAndDecrementStock(payment.getOrder().getId());
        }

        return toResponse(payment, gateway.providerName(), result.message());
    }

    @Transactional(readOnly = true)
    public PaymentResponse getStatus(Long userId, Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> ApiException.notFound("Payment not found"));
        requireOwner(payment.getOrder(), userId);
        // The database is the source of truth. The dummy provider's in-memory ledger
        // disappears on restart and must not override a persisted successful payment.
        return toResponse(payment, payment.getProvider(), "Status: " + payment.getStatus());
    }

    private void requireOwner(Order order, Long userId) {
        if (!order.getUser().getId().equals(userId)) {
            throw ApiException.forbidden("Not your order");
        }
    }

    private Payment.Status mapStatus(PaymentGatewayService.GatewayStatus status) {
        return switch (status) {
            case SUCCESS -> Payment.Status.SUCCESS;
            case FAILED -> Payment.Status.FAILED;
            case INITIATED, PENDING -> Payment.Status.INITIATED;
        };
    }

    private PaymentResponse toResponse(Payment p, String providerName, String message) {
        boolean testMode = "dummy".equals(providerName);
        return new PaymentResponse(p.getId(), p.getOrder().getId(), p.getMethod().name(), p.getStatus().name(),
                p.getAmount(), p.getProvider(), p.getProviderReference(),
                p.getFailureReason() != null ? p.getFailureReason() : message, testMode);
    }
}
