package com.gadgetly.service;

import com.gadgetly.entity.Order;
import com.gadgetly.entity.OrderItem;
import com.gadgetly.entity.Address;
import com.gadgetly.entity.Cart;
import com.gadgetly.entity.CartItem;
import com.gadgetly.entity.Coupon;
import com.gadgetly.entity.Product;
import com.gadgetly.entity.ProductVariant;
import com.gadgetly.entity.Payment;
import com.gadgetly.entity.User;
import com.gadgetly.dto.CheckoutRequest;
import com.gadgetly.exception.ApiException;
import com.gadgetly.repository.AddressRepository;
import com.gadgetly.repository.CartRepository;
import com.gadgetly.repository.CouponRepository;
import com.gadgetly.repository.OrderRepository;
import com.gadgetly.repository.PaymentRepository;
import com.gadgetly.repository.ProductRepository;
import com.gadgetly.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock CartRepository cartRepository;
    @Mock AddressRepository addressRepository;
    @Mock CouponRepository couponRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock ProductRepository productRepository;
    @Mock ProductVariantRepository variantRepository;
    @InjectMocks OrderService service;

    private Order order;
    private Product product;

    @BeforeEach
    void setUp() {
        User owner = new User();
        owner.setId(7L);
        order = new Order();
        order.setId(11L);
        order.setUser(owner);
        order.setOrderNumber("JYT-TEST");
        order.setStatus(Order.Status.PENDING);
        order.setSubtotal(BigDecimal.valueOf(100));
        order.setTotal(BigDecimal.valueOf(100));

        product = new Product();
        product.setId(3L);
        product.setStock(3);
        OrderItem item = new OrderItem();
        item.setProduct(product);
        item.setProductNameSnapshot("Test product");
        item.setQuantity(2);
        item.setUnitPrice(BigDecimal.valueOf(50));
        order.getItems().add(item);
    }

    @Test
    void customerCancellationRestoresReservedStockOnce() {
        when(orderRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(order));
        when(productRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(product));
        when(orderRepository.save(order)).thenReturn(order);

        assertEquals("CANCELLED", service.cancelMyOrder(7L, 11L).status());
        assertEquals(5, product.getStock());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> service.cancelMyOrder(7L, 11L)).getStatus());
        assertEquals(5, product.getStock());
        verify(productRepository, times(1)).save(product);
    }

    @Test
    void cancellingUnpaidOrderReleasesOneUseCouponQuota() {
        order.setCouponCode("ONEUSE");
        Coupon coupon = new Coupon();
        coupon.setCode("ONEUSE");
        coupon.setUsageLimit(1);
        coupon.setUsageCount(1);
        when(orderRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(order));
        when(productRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(product));
        when(paymentRepository.findByOrderId(11L)).thenReturn(Optional.empty());
        when(couponRepository.findByCodeIgnoreCaseForUpdate("ONEUSE")).thenReturn(Optional.of(coupon));
        when(orderRepository.save(order)).thenReturn(order);

        service.cancelMyOrder(7L, 11L);

        assertEquals(0, coupon.getUsageCount());
        verify(couponRepository).save(coupon);
    }

    @Test
    void customerCannotCancelAnotherCustomersOrder() {
        when(orderRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(order));

        ApiException ex = assertThrows(ApiException.class, () -> service.cancelMyOrder(99L, 11L));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals(3, product.getStock());
        verify(orderRepository, never()).save(order);
    }

    @Test
    void cancelledOrderCannotBeReopenedWithoutNewStockReservation() {
        order.setStatus(Order.Status.CANCELLED);
        when(orderRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(order));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.adminUpdateStatus(11L, Order.Status.CONFIRMED));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(orderRepository, never()).save(order);
    }

    @Test
    void shippedOrderCannotBeCancelledAndRestocked() {
        order.setStatus(Order.Status.SHIPPED);
        when(orderRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(order));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.adminUpdateStatus(11L, Order.Status.CANCELLED));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(3, product.getStock());
        verify(productRepository, never()).save(product);
    }

    @Test
    void adminCannotConfirmOrderWithoutAcceptedPayment() {
        when(orderRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(11L)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.adminUpdateStatus(11L, Order.Status.CONFIRMED));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(Order.Status.PENDING, order.getStatus());
        verify(orderRepository, never()).save(order);
    }

    @Test
    void adminCanConfirmLegacyAcceptedCodOrder() {
        Payment acceptedCod = new Payment();
        acceptedCod.setMethod(Payment.Method.COD);
        acceptedCod.setStatus(Payment.Status.INITIATED);
        when(orderRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(11L)).thenReturn(Optional.of(acceptedCod));
        when(orderRepository.save(order)).thenReturn(order);

        assertEquals("CONFIRMED", service.adminUpdateStatus(11L, Order.Status.CONFIRMED).status());
    }

    @Test
    void cancellationRestoresVariantStock() {
        ProductVariant variant = new ProductVariant();
        variant.setId(8L);
        variant.setProduct(product);
        variant.setStock(4);
        order.getItems().getFirst().setVariant(variant);
        when(orderRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(order));
        when(productRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(product));
        when(variantRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(variant));
        when(orderRepository.save(order)).thenReturn(order);

        service.cancelMyOrder(7L, 11L);

        assertEquals(6, variant.getStock());
        assertEquals(3, product.getStock());
        verify(productRepository, never()).save(product);
    }

    @Test
    void checkoutRejectsCombinedQuantityAboveLockedStock() {
        Cart cart = new Cart();
        cart.setUser(order.getUser());
        CartItem first = new CartItem();
        first.setProduct(product);
        first.setQuantity(2);
        CartItem second = new CartItem();
        second.setProduct(product);
        second.setQuantity(2);
        cart.getItems().add(first);
        cart.getItems().add(second);
        Address address = new Address();
        address.setUser(order.getUser());
        when(cartRepository.findByUserIdForUpdate(7L)).thenReturn(Optional.of(cart));
        when(addressRepository.findById(5L)).thenReturn(Optional.of(address));
        when(productRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(product));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.checkout(7L, new CheckoutRequest(5L, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(3, product.getStock());
        verify(cartRepository).findByUserIdForUpdate(7L);
        verify(productRepository).findByIdForUpdate(3L);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void checkoutRejectsCouponThatExpiredAfterItWasAppliedToCart() {
        Cart cart = new Cart();
        cart.setUser(order.getUser());
        cart.setAppliedCouponCode("EXPIRED");
        CartItem item = new CartItem();
        item.setProduct(product);
        item.setQuantity(1);
        cart.getItems().add(item);
        product.setPrice(BigDecimal.valueOf(100));
        Address address = new Address();
        address.setUser(order.getUser());
        Coupon coupon = new Coupon();
        coupon.setCode("EXPIRED");
        coupon.setExpiresAt(Instant.now().minusSeconds(60));
        when(cartRepository.findByUserIdForUpdate(7L)).thenReturn(Optional.of(cart));
        when(addressRepository.findById(5L)).thenReturn(Optional.of(address));
        when(productRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(product));
        when(couponRepository.findByCodeIgnoreCaseForUpdate("EXPIRED")).thenReturn(Optional.of(coupon));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.checkout(7L, new CheckoutRequest(5L, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(couponRepository).findByCodeIgnoreCaseForUpdate("EXPIRED");
        verify(orderRepository, never()).save(any());
    }

    @Test
    void checkoutRejectsOneUseCouponAlreadyRedeemed() {
        Cart cart = new Cart();
        cart.setUser(order.getUser());
        cart.setAppliedCouponCode("ONEUSE");
        CartItem item = new CartItem();
        item.setProduct(product);
        item.setQuantity(1);
        cart.getItems().add(item);
        product.setPrice(BigDecimal.valueOf(100));
        Address address = new Address();
        address.setUser(order.getUser());
        Coupon coupon = new Coupon();
        coupon.setCode("ONEUSE");
        coupon.setUsageLimit(1);
        coupon.setUsageCount(1);
        when(cartRepository.findByUserIdForUpdate(7L)).thenReturn(Optional.of(cart));
        when(addressRepository.findById(5L)).thenReturn(Optional.of(address));
        when(productRepository.findByIdForUpdate(3L)).thenReturn(Optional.of(product));
        when(couponRepository.findByCodeIgnoreCaseForUpdate("ONEUSE")).thenReturn(Optional.of(coupon));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.checkout(7L, new CheckoutRequest(5L, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(couponRepository).findByCodeIgnoreCaseForUpdate("ONEUSE");
        verify(orderRepository, never()).save(any());
    }
}
