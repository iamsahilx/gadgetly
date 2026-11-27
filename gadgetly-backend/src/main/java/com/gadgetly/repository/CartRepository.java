package com.gadgetly.repository;

import com.gadgetly.entity.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.user.id = :userId")
    Optional<Cart> findByUserIdForUpdate(@Param("userId") Long userId);

    Optional<Cart> findByUserId(Long userId);
}
