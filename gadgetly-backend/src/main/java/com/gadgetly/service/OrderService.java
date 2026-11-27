package com.gadgetly.service;

import com.gadgetly.dto.CheckoutRequest;
import com.gadgetly.dto.OrderResponse;
import com.gadgetly.entity.*;
import com.gadgetly.exception.ApiException;
import com.gadgetly.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final AddressRepository addressRepository;
    private final CouponRepository couponRepository;
    private final PaymentRepository paymentRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

    /**
     * Creates an order from the user's current cart in PENDING status and immediately
     * decrements stock so that inventory is reserved at checkout time.
     */
    @Transactional
    public OrderResponse checkout(Long userId, CheckoutRequest req) {
        Cart cart = cartRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> ApiException.badRequest("Cart is empty"));
        if (cart.getItems().isEmpty()) {
            throw ApiException.badRequest("Cart is empty");
        }

        Address address = addressRepository.findById(req.addressId())
                .orElseThrow(() -> ApiException.notFound("Address not found"));
        if (!address.getUser().getId().equals(userId)) {
            throw ApiException.forbidden("Address does not belong to this user");
        }

        // Lock stock rows in a stable order before checking quantities. Otherwise two
        // checkouts can both observe the same last unit and oversell it.
        Map<Long, Product> products = new HashMap<>();
        cart.getItems().stream().map(ci -> ci.getProduct().getId()).distinct().sorted()
                .forEach(id -> products.put(id, productRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> ApiException.notFound("Product not found"))));
        Map<Long, ProductVariant> variants = new HashMap<>();
        cart.getItems().stream().filter(ci -> ci.getVariant() != null)
                .map(ci -> ci.getVariant().getId()).distinct().sorted()
                .forEach(id -> variants.put(id, variantRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> ApiException.notFound("Variant not found"))));

        Map<Long, Long> productQuantities = new HashMap<>();
        Map<Long, Long> variantQuantities = new HashMap<>();
        for (CartItem ci : cart.getItems()) {
            Product product = products.get(ci.getProduct().getId());
            if (!product.isActive()) throw ApiException.badRequest("Product is no longer available: " + product.getName());
            if (ci.getQuantity() == null || ci.getQuantity() <= 0) {
                throw ApiException.badRequest("Invalid quantity for " + product.getName());
            }
            if (ci.getVariant() == null) {
                productQuantities.merge(product.getId(), ci.getQuantity().longValue(), Long::sum);
            } else {
                ProductVariant variant = variants.get(ci.getVariant().getId());
                if (!variant.getProduct().getId().equals(product.getId())) {
                    throw ApiException.badRequest("Variant does not belong to " + product.getName());
                }
                variantQuantities.merge(variant.getId(), ci.getQuantity().longValue(), Long::sum);
            }
        }
        productQuantities.forEach((id, quantity) -> {
            if (products.get(id).getStock() < quantity) {
                throw ApiException.badRequest("Insufficient stock for " + products.get(id).getName());
            }
        });
        variantQuantities.forEach((id, quantity) -> {
            if (variants.get(id).getStock() < quantity) {
                throw ApiException.badRequest("Insufficient stock for " + variants.get(id).getProduct().getName());
            }
        });

        BigDecimal subtotal = BigDecimal.ZERO;
        Order order = new Order();
        order.setOrderNumber("JYT" + Instant.now().toEpochMilli() + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        User userRef = new User();
        userRef.setId(userId);
        order.setUser(userRef);

        Order.ShippingSnapshot snapshot = new Order.ShippingSnapshot();
        snapshot.setRecipientName(address.getRecipientName());
        snapshot.setPhone(address.getPhone());
        snapshot.setLine1(address.getLine1());
        snapshot.setLine2(address.getLine2());
        snapshot.setCity(address.getCity());
        snapshot.setState(address.getState());
        snapshot.setPincode(address.getPincode());
        order.setShippingAddress(snapshot);

        for (CartItem ci : cart.getItems()) {
            Product product = products.get(ci.getProduct().getId());
            ProductVariant variant = ci.getVariant() != null ? variants.get(ci.getVariant().getId()) : null;
            BigDecimal unitPrice = product.getPrice();
            if (variant != null) unitPrice = unitPrice.add(variant.getPriceDelta());

            OrderItem oi = new OrderItem();
            oi.setOrder(order);
            oi.setProduct(product);
            oi.setVariant(variant);
            oi.setProductNameSnapshot(product.getName());
            oi.setQuantity(ci.getQuantity());
            oi.setUnitPrice(unitPrice);
            order.getItems().add(oi);

            subtotal = subtotal.add(unitPrice.multiply(BigDecimal.valueOf(ci.getQuantity())));

            // Decrement stock immediately at checkout
            if (variant != null) {
                variant.setStock(variant.getStock() - ci.getQuantity());
            } else {
                product.setStock(product.getStock() - ci.getQuantity());
                productRepository.save(product);
            }
        }

        BigDecimal discount = BigDecimal.ZERO;
        String couponCode = req.couponCode() != null ? req.couponCode() : cart.getAppliedCouponCode();
        if (couponCode != null) {
            Coupon coupon = couponRepository.findByCodeIgnoreCaseForUpdate(couponCode)
                    .filter(c -> c.isUsableAt(Instant.now()))
                    .orElseThrow(() -> ApiException.badRequest("Invalid or expired coupon"));
            if (subtotal.compareTo(coupon.getMinOrderValue() != null ? coupon.getMinOrderValue() : BigDecimal.ZERO) >= 0) {
                discount = coupon.getDiscountType() == Coupon.DiscountType.FLAT
                        ? coupon.getDiscountValue()
                        : subtotal.multiply(coupon.getDiscountValue()).divide(BigDecimal.valueOf(100))
                                .setScale(2, java.math.RoundingMode.HALF_UP);
                if (discount.compareTo(subtotal) > 0) discount = subtotal;
                order.setCouponCode(coupon.getCode());
                coupon.setUsageCount((coupon.getUsageCount() == null ? 0 : coupon.getUsageCount()) + 1);
                couponRepository.save(coupon);
            }
        }

        BigDecimal shippingFee = subtotal.subtract(discount).compareTo(BigDecimal.valueOf(999)) >= 0
                ? BigDecimal.ZERO : BigDecimal.valueOf(99);

        order.setSubtotal(subtotal);
        order.setDiscount(discount);
        order.setShippingFee(shippingFee);
        order.setTotal(subtotal.subtract(discount).add(shippingFee));
        order.setStatus(Order.Status.PENDING);

        order = orderRepository.save(order);

        // clear cart after order is created
        cart.getItems().clear();
        cart.setAppliedCouponCode(null);
        cartRepository.save(cart);

        return toResponse(order);
    }

    @Transactional
    public void markConfirmedAndDecrementStock(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        if (order.getStatus() != Order.Status.PENDING) return; // idempotent

        // Stock is already decremented at checkout time, so just update status
        order.setStatus(Order.Status.CONFIRMED);
        orderRepository.save(order);
        System.out.println("[ORDER CONFIRMED] " + order.getOrderNumber() + " -> confirmation sent (mock).");
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> myOrders(Long userId, Pageable pageable) {
        return orderRepository.findByUserId(userId, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public OrderResponse getMyOrder(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (!order.getUser().getId().equals(userId)) throw ApiException.forbidden("Not your order");
        return toResponse(order);
    }

    @Transactional
    public OrderResponse cancelMyOrder(Long userId, Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        if (!order.getUser().getId().equals(userId)) throw ApiException.forbidden("Not your order");
        if (order.getStatus() != Order.Status.PENDING) {
            throw ApiException.conflict("Only pending orders can be cancelled");
        }
        restoreStock(order);
        releasePendingCoupon(order);
        order.setStatus(Order.Status.CANCELLED);
        return toResponse(orderRepository.save(order));
    }

    // ---- admin ----

    @Transactional(readOnly = true)
    public Page<OrderResponse> adminList(Order.Status status, Pageable pageable) {
        Page<Order> page = status != null ? orderRepository.findByStatus(status, pageable) : orderRepository.findAll(pageable);
        return page.map(this::toResponse);
    }

    @Transactional
    public OrderResponse adminUpdateStatus(Long orderId, Order.Status newStatus) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        Order.Status oldStatus = order.getStatus();

        if (oldStatus == newStatus) return toResponse(order);
        if (oldStatus == Order.Status.CANCELLED || oldStatus == Order.Status.REFUNDED) {
            throw ApiException.conflict("Finalized orders cannot be reopened");
        }
        if (newStatus == Order.Status.CANCELLED &&
                (oldStatus == Order.Status.SHIPPED || oldStatus == Order.Status.DELIVERED)) {
            throw ApiException.conflict("Shipped orders cannot be cancelled and restocked");
        }
        if (newStatus == Order.Status.REFUNDED && oldStatus == Order.Status.PENDING) {
            throw ApiException.conflict("An unpaid order cannot be refunded");
        }
        if (oldStatus == Order.Status.PENDING && newStatus != Order.Status.CANCELLED) {
            Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
            if (!isAcceptedPayment(payment)) {
                throw ApiException.conflict("Order is awaiting an accepted payment");
            }
        }
        if (newStatus.ordinal() < oldStatus.ordinal()) {
            throw ApiException.conflict("Order status cannot move backwards");
        }

        // If cancelling a non-cancelled order, restore stock
        if (newStatus == Order.Status.CANCELLED) {
            restoreStock(order);
            if (oldStatus == Order.Status.PENDING) releasePendingCoupon(order);
        }

        order.setStatus(newStatus);
        return toResponse(orderRepository.save(order));
    }

    private void restoreStock(Order order) {
        // Use the same product-then-variant lock order as checkout. Restoring a
        // cancelled order must not overwrite stock reserved by another checkout.
        Map<Long, Product> products = new HashMap<>();
        order.getItems().stream().map(oi -> oi.getProduct().getId()).distinct().sorted()
                .forEach(id -> products.put(id, productRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> ApiException.notFound("Product not found"))));
        Map<Long, ProductVariant> variants = new HashMap<>();
        order.getItems().stream().filter(oi -> oi.getVariant() != null)
                .map(oi -> oi.getVariant().getId()).distinct().sorted()
                .forEach(id -> variants.put(id, variantRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> ApiException.notFound("Variant not found"))));

        for (OrderItem oi : order.getItems()) {
            if (oi.getVariant() != null) {
                ProductVariant variant = variants.get(oi.getVariant().getId());
                variant.setStock(variant.getStock() + oi.getQuantity());
            } else {
                Product product = products.get(oi.getProduct().getId());
                product.setStock(product.getStock() + oi.getQuantity());
                productRepository.save(product);
            }
        }
    }

    private void releasePendingCoupon(Order order) {
        if (order.getCouponCode() == null) return;
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElse(null);
        if (isAcceptedPayment(payment)) return;

        couponRepository.findByCodeIgnoreCaseForUpdate(order.getCouponCode()).ifPresent(coupon -> {
            coupon.setUsageCount(Math.max(0, coupon.getUsageCount() == null ? 0 : coupon.getUsageCount() - 1));
            couponRepository.save(coupon);
        });
    }

    private boolean isAcceptedPayment(Payment payment) {
        return payment != null
                && (payment.getStatus() == Payment.Status.SUCCESS
                || (payment.getMethod() == Payment.Method.COD
                        && payment.getStatus() == Payment.Status.INITIATED));
    }

    public long countPending() {
        return orderRepository.countByStatus(Order.Status.PENDING);
    }

    @Transactional
    public Order getEntity(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
    }

    private OrderResponse toResponse(Order o) {
        List<OrderResponse.Item> items = o.getItems().stream()
                .map(i -> new OrderResponse.Item(i.getProduct().getId(), i.getProductNameSnapshot(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        String customerName = null;
        String customerEmail = null;
        try {
            User user = o.getUser();
            if (user != null) {
                customerName = user.getFullName();
                customerEmail = user.getEmail();
            }
        } catch (Exception ignored) {
            // lazy loading may fail for detached entities
        }
        return new OrderResponse(o.getId(), o.getOrderNumber(), o.getStatus().name(), items,
                o.getSubtotal(), o.getDiscount(), o.getShippingFee(), o.getTotal(), o.getCouponCode(),
                customerName, customerEmail, o.getCreatedAt());
    }
}
