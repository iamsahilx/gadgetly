package com.gadgetly.dto;

import jakarta.validation.constraints.NotNull;

public record CheckoutRequest(
        @NotNull Long addressId,
        String couponCode
) {}
