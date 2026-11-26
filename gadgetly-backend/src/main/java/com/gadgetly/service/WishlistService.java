package com.gadgetly.service;

import com.gadgetly.dto.ProductResponse;
import com.gadgetly.entity.Product;
import com.gadgetly.entity.User;
import com.gadgetly.entity.Wishlist;
import com.gadgetly.exception.ApiException;
import com.gadgetly.repository.ProductRepository;
import com.gadgetly.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final ProductRepository productRepository;
    private final ProductService productService;

    @Transactional(readOnly = true)
    public List<ProductResponse> list(Long userId) {
        return wishlistRepository.findByUserId(userId).stream()
                .map(Wishlist::getProduct)
                .map(productService::toResponse)
                .toList();
    }

    @Transactional
    public void add(Long userId, Long productId) {
        if (wishlistRepository.findByUserIdAndProductId(userId, productId).isPresent()) return;
        Product product = productRepository.findById(productId).orElseThrow(() -> ApiException.notFound("Product not found"));
        Wishlist w = new Wishlist();
        User ref = new User();
        ref.setId(userId);
        w.setUser(ref);
        w.setProduct(product);
        wishlistRepository.save(w);
    }

    @Transactional
    public void remove(Long userId, Long productId) {
        wishlistRepository.findByUserIdAndProductId(userId, productId).ifPresent(wishlistRepository::delete);
    }
}
