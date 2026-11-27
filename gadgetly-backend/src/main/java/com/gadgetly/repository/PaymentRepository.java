package com.gadgetly.repository;

import com.gadgetly.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    @Query("select p.order.id from Payment p where p.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);

    Optional<Payment> findByOrderId(Long orderId);
    Optional<Payment> findByProviderReference(String providerReference);
}
