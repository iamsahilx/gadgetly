package com.gadgetly.service;

import com.gadgetly.dto.PaymentInitiateRequest;
import com.gadgetly.entity.Order;
import com.gadgetly.entity.Payment;
import com.gadgetly.entity.User;
import com.gadgetly.exception.ApiException;
import com.gadgetly.repository.PaymentRepository;
import com.gadgetly.service.payment.PaymentGatewayService;
import com.gadgetly.service.payment.PaymentProviderResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentProviderResolver providerResolver;
    @Mock OrderService orderService;
    @Mock PaymentGatewayService gateway;
    @InjectMocks PaymentService service;

    private Order order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        User owner = new User();
        owner.setId(7L);
        order = new Order();
        order.setId(11L);
        order.setUser(owner);
        order.setStatus(Order.Status.PENDING);
        order.setTotal(BigDecimal.valueOf(1499));

        payment = new Payment();
        payment.setId(23L);
        payment.setOrder(order);
        payment.setMethod(Payment.Method.UPI);
        payment.setAmount(order.getTotal());
        payment.setProvider("dummy");
        payment.setProviderReference("DUMMY-TXN-ABC");
        payment.setStatus(Payment.Status.INITIATED);
    }

    @Test
    void otherCustomerCannotInitiatePaymentForOrder() {
        when(orderService.getEntity(11L)).thenReturn(order);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.initiate(99L, upiRequest()));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        verifyNoInteractions(paymentRepository, providerResolver);
    }

    @Test
    void otherCustomerCannotReadOrVerifyPayment() {
        when(paymentRepository.findOrderIdById(23L)).thenReturn(Optional.of(11L));
        when(orderService.getEntity(11L)).thenReturn(order);
        when(paymentRepository.findById(23L)).thenReturn(Optional.of(payment));

        ApiException verifyError = assertThrows(ApiException.class,
                () -> service.verify(99L, 23L, "DUMMY-TXN-ABC"));
        ApiException statusError = assertThrows(ApiException.class,
                () -> service.getStatus(99L, 23L));

        assertEquals(HttpStatus.FORBIDDEN, verifyError.getStatus());
        assertEquals(HttpStatus.FORBIDDEN, statusError.getStatus());
        verifyNoInteractions(providerResolver);
    }

    @Test
    void cannotVerifyAnotherTransactionReference() {
        when(paymentRepository.findOrderIdById(23L)).thenReturn(Optional.of(11L));
        when(orderService.getEntity(11L)).thenReturn(order);
        when(paymentRepository.findById(23L)).thenReturn(Optional.of(payment));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.verify(7L, 23L, "DUMMY-TXN-SOMEONE-ELSE"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(providerResolver);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void failedAttemptCanRetrySameOrderAndClearOldFailure() {
        payment.setStatus(Payment.Status.FAILED);
        payment.setFailureReason("Declined");
        when(orderService.getEntity(11L)).thenReturn(order);
        when(paymentRepository.findByOrderId(11L)).thenReturn(Optional.of(payment));
        when(providerResolver.resolve()).thenReturn(gateway);
        when(gateway.providerName()).thenReturn("dummy");
        when(gateway.initiate(any())).thenReturn(new PaymentGatewayService.GatewayInitiateResult(
                "DUMMY-TXN-RETRY", PaymentGatewayService.GatewayStatus.SUCCESS, "Approved", true));
        when(paymentRepository.save(payment)).thenReturn(payment);

        var response = service.initiate(7L, upiRequest());

        assertEquals(23L, response.paymentId());
        assertEquals("SUCCESS", response.status());
        assertNull(payment.getFailureReason());
        assertEquals("DUMMY-TXN-RETRY", payment.getProviderReference());
        verify(orderService).markConfirmedAndDecrementStock(11L);
    }

    @Test
    void declinedAttemptLeavesOrderPendingForRetry() {
        when(orderService.getEntity(11L)).thenReturn(order);
        when(paymentRepository.findByOrderId(11L)).thenReturn(Optional.empty());
        when(providerResolver.resolve()).thenReturn(gateway);
        when(gateway.providerName()).thenReturn("dummy");
        when(gateway.initiate(any())).thenReturn(new PaymentGatewayService.GatewayInitiateResult(
                "DUMMY-TXN-DECLINED", PaymentGatewayService.GatewayStatus.FAILED, "Declined", true));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment saved = invocation.getArgument(0);
            saved.setId(24L);
            return saved;
        });

        var response = service.initiate(7L, upiRequest());

        assertEquals("FAILED", response.status());
        assertEquals(Order.Status.PENDING, order.getStatus());
        verify(orderService, never()).markConfirmedAndDecrementStock(any());
    }

    @Test
    void acceptedCashOnDeliveryConfirmsOrderButLeavesPaymentDue() {
        when(orderService.getEntity(11L)).thenReturn(order);
        when(paymentRepository.findByOrderId(11L)).thenReturn(Optional.empty());
        when(providerResolver.resolve()).thenReturn(gateway);
        when(gateway.providerName()).thenReturn("dummy");
        when(gateway.initiate(any())).thenReturn(new PaymentGatewayService.GatewayInitiateResult(
                "DUMMY-TXN-COD", PaymentGatewayService.GatewayStatus.INITIATED, "Pay on delivery", true));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment saved = invocation.getArgument(0);
            saved.setId(24L);
            return saved;
        });

        var response = service.initiate(7L,
                new PaymentInitiateRequest(11L, "COD", null, null, null, null, null));

        assertEquals("INITIATED", response.status());
        verify(orderService).markConfirmedAndDecrementStock(11L);
    }

    @Test
    void duplicateInitiationDoesNotCallGatewayTwice() {
        when(orderService.getEntity(11L)).thenReturn(order);
        when(paymentRepository.findByOrderId(11L)).thenReturn(Optional.of(payment));

        var response = service.initiate(7L, upiRequest());

        assertEquals(23L, response.paymentId());
        assertEquals("INITIATED", response.status());
        verifyNoInteractions(providerResolver);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void verificationUsesStoredReferenceAndUpdatesOrderOnce() {
        when(paymentRepository.findOrderIdById(23L)).thenReturn(Optional.of(11L));
        when(orderService.getEntity(11L)).thenReturn(order);
        when(paymentRepository.findById(23L)).thenReturn(Optional.of(payment));
        when(providerResolver.resolve()).thenReturn(gateway);
        when(gateway.providerName()).thenReturn("dummy");
        when(gateway.verify("DUMMY-TXN-ABC")).thenReturn(new PaymentGatewayService.GatewayVerifyResult(
                "DUMMY-TXN-ABC", PaymentGatewayService.GatewayStatus.SUCCESS, "Settled"));
        when(paymentRepository.save(payment)).thenReturn(payment);

        assertEquals("SUCCESS", service.verify(7L, 23L, "DUMMY-TXN-ABC").status());
        assertEquals("SUCCESS", service.verify(7L, 23L, "DUMMY-TXN-ABC").status());

        verify(gateway, times(1)).verify("DUMMY-TXN-ABC");
        verify(orderService, times(1)).markConfirmedAndDecrementStock(11L);
    }

    @Test
    void persistedSuccessIsReportedWithoutDummyLedger() {
        payment.setStatus(Payment.Status.SUCCESS);
        when(paymentRepository.findById(23L)).thenReturn(Optional.of(payment));

        assertEquals("SUCCESS", service.getStatus(7L, 23L).status());
        verifyNoInteractions(providerResolver);
    }

    private PaymentInitiateRequest upiRequest() {
        return new PaymentInitiateRequest(11L, "UPI", null, null, null, "success@upi", null);
    }
}
