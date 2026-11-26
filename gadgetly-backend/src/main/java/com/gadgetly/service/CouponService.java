package com.gadgetly.service;

import com.gadgetly.dto.CouponRequest;
import com.gadgetly.entity.Coupon;
import com.gadgetly.exception.ApiException;
import com.gadgetly.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;

    public List<Coupon> list() {
        return couponRepository.findAll();
    }

    @Transactional
    public Coupon create(CouponRequest req) {
        if (couponRepository.findByCodeIgnoreCase(req.code()).isPresent()) {
            throw ApiException.conflict("Coupon code already exists");
        }
        Coupon coupon = new Coupon();
        apply(coupon, req);
        return couponRepository.save(coupon);
    }

    @Transactional
    public Coupon update(Long id, CouponRequest req) {
        Coupon coupon = couponRepository.findById(id).orElseThrow(() -> ApiException.notFound("Coupon not found"));
        apply(coupon, req);
        return couponRepository.save(coupon);
    }

    @Transactional
    public void delete(Long id) {
        if (!couponRepository.existsById(id)) throw ApiException.notFound("Coupon not found");
        couponRepository.deleteById(id);
    }

    private void apply(Coupon c, CouponRequest req) {
        c.setCode(req.code().toUpperCase());
        c.setDiscountType(Coupon.DiscountType.valueOf(req.discountType().toUpperCase()));
        c.setDiscountValue(req.discountValue());
        c.setMinOrderValue(req.minOrderValue());
        c.setExpiresAt(req.expiresAt());
        c.setUsageLimit(req.usageLimit());
        c.setActive(req.active());
    }
}
