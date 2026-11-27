package com.gadgetly.service;

import com.gadgetly.dto.CartItemRequest;
import com.gadgetly.entity.Cart;
import com.gadgetly.entity.Product;
import com.gadgetly.entity.ProductVariant;
import com.gadgetly.exception.ApiException;
import com.gadgetly.repository.CartItemRepository;
import com.gadgetly.repository.CartRepository;
import com.gadgetly.repository.CouponRepository;
import com.gadgetly.repository.ProductRepository;
import com.gadgetly.repository.ProductVariantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock CartRepository cartRepository;
    @Mock CartItemRepository cartItemRepository;
    @Mock ProductRepository productRepository;
    @Mock ProductVariantRepository variantRepository;
    @Mock CouponRepository couponRepository;
    @InjectMocks CartService service;

    @Test
    void rejectsVariantFromAnotherProductBeforeSavingCart() {
        Cart cart = new Cart();
        cart.setId(1L);
        Product product = new Product();
        product.setId(3L);
        Product otherProduct = new Product();
        otherProduct.setId(4L);
        ProductVariant variant = new ProductVariant();
        variant.setId(8L);
        variant.setProduct(otherProduct);
        when(cartRepository.findByUserId(7L)).thenReturn(Optional.of(cart));
        when(productRepository.findById(3L)).thenReturn(Optional.of(product));
        when(variantRepository.findById(8L)).thenReturn(Optional.of(variant));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.addItem(7L, new CartItemRequest(3L, 8L, 1)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verifyNoInteractions(cartItemRepository);
    }
}
