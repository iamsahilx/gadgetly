package com.gadgetly.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gadgetly.dto.ProductRequest;
import com.gadgetly.dto.ProductResponse;
import com.gadgetly.entity.*;
import com.gadgetly.exception.ApiException;
import com.gadgetly.repository.CategoryRepository;
import com.gadgetly.repository.ProductRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional(readOnly = true)
    public Page<ProductResponse> search(String q, Long categoryId, String brand,
                                         BigDecimal minPrice, BigDecimal maxPrice,
                                         Double minRating, Boolean featured, Pageable pageable) {
        // A storefront category includes products in its subcategories as well.
        Set<Long> categoryIds = categoryId == null ? Set.of() : categoryAndDescendantIds(categoryId);
        Specification<Product> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.isTrue(root.get("active")));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("brand")), like)
                ));
            }
            if (categoryId != null) predicates.add(root.get("category").get("id").in(categoryIds));
            if (brand != null && !brand.isBlank()) predicates.add(cb.equal(cb.lower(root.get("brand")), brand.toLowerCase()));
            if (minPrice != null) predicates.add(cb.ge(root.get("price"), minPrice));
            if (maxPrice != null) predicates.add(cb.le(root.get("price"), maxPrice));
            if (minRating != null) predicates.add(cb.ge(root.get("ratingAverage"), minRating));
            if (featured != null) predicates.add(cb.equal(root.get("featured"), featured));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return productRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> listForAdmin(Pageable pageable) {
        return productRepository.findAll(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public ProductResponse getBySlug(String slug) {
        Product product = productRepository.findBySlug(slug)
                .orElseThrow(() -> ApiException.notFound("Product not found"));
        if (!product.isActive()) throw ApiException.notFound("Product not found");
        return toResponse(product);
    }

    @Transactional(readOnly = true)
    public ProductResponse getById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Product not found"));
        if (!product.isActive()) throw ApiException.notFound("Product not found");
        return toResponse(product);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getRelated(Long productId, int limit) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> ApiException.notFound("Product not found"));
        Specification<Product> spec = (root, query, cb) -> cb.and(
                cb.isTrue(root.get("active")),
                cb.equal(root.get("category").get("id"), product.getCategory().getId()),
                cb.notEqual(root.get("id"), product.getId())
        );
        return productRepository.findAll(spec, org.springframework.data.domain.PageRequest.of(0, limit))
                .map(this::toResponse).getContent();
    }

    @Transactional
    public ProductResponse create(ProductRequest req) {
        if (productRepository.existsBySku(req.sku())) {
            throw ApiException.conflict("SKU already exists: " + req.sku());
        }
        Category category = categoryRepository.findById(req.categoryId())
                .orElseThrow(() -> ApiException.notFound("Category not found"));

        Product product = new Product();
        applyRequest(product, req, category);
        product = productRepository.save(product);
        return toResponse(product);
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest req) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Product not found"));
        if (!product.getSku().equals(req.sku()) && productRepository.existsBySku(req.sku())) {
            throw ApiException.conflict("SKU already exists: " + req.sku());
        }
        Category category = categoryRepository.findById(req.categoryId())
                .orElseThrow(() -> ApiException.notFound("Category not found"));
        applyRequest(product, req, category);
        return toResponse(productRepository.save(product));
    }

    @Transactional
    public void delete(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Product not found"));
        // Orders, cart items, and reviews reference products. Hide instead of deleting history.
        product.setActive(false);
    }

    private void applyRequest(Product product, ProductRequest req, Category category) {
        if (req.mrp().compareTo(req.price()) < 0) {
            throw ApiException.badRequest("MRP cannot be lower than the selling price");
        }
        if (req.variants() != null) {
            for (ProductRequest.VariantRequest variant : req.variants()) {
                BigDecimal delta = variant.priceDelta() != null ? variant.priceDelta() : BigDecimal.ZERO;
                if (req.price().add(delta).compareTo(BigDecimal.ZERO) < 0) {
                    throw ApiException.badRequest("Variant price cannot be negative");
                }
                if (variant.stock() != null && variant.stock() < 0) {
                    throw ApiException.badRequest("Variant stock cannot be negative");
                }
            }
        }
        product.setName(req.name());
        product.setSlug(SlugUtil.toSlug(req.name()) + "-" + req.sku().toLowerCase());
        product.setSku(req.sku());
        product.setCategory(category);
        product.setBrand(req.brand());
        product.setDescription(req.description());
        product.setPrice(req.price());
        product.setMrp(req.mrp());
        product.setStock(req.stock() != null ? req.stock() : 0);
        product.setFeatured(req.featured());
        product.setActive(req.active());
        try {
            product.setSpecsJson(objectMapper.writeValueAsString(req.specs() != null ? req.specs() : Map.of()));
        } catch (Exception e) {
            product.setSpecsJson("{}");
        }

        if (req.imageUrls() != null) {
            product.getImages().clear();
            int order = 0;
            for (String url : req.imageUrls()) {
                ProductImage img = new ProductImage();
                img.setProduct(product);
                img.setUrl(url);
                img.setDisplayOrder(order);
                img.setPrimary(order == 0);
                product.getImages().add(img);
                order++;
            }
        }

        if (req.variants() != null) {
            product.getVariants().clear();
            for (ProductRequest.VariantRequest v : req.variants()) {
                ProductVariant variant = new ProductVariant();
                variant.setProduct(product);
                variant.setVariantType(v.variantType());
                variant.setVariantValue(v.variantValue());
                variant.setPriceDelta(v.priceDelta() != null ? v.priceDelta() : BigDecimal.ZERO);
                variant.setStock(v.stock() != null ? v.stock() : 0);
                variant.setSku(v.sku());
                product.getVariants().add(variant);
            }
        }
    }

    private Set<Long> categoryAndDescendantIds(Long categoryId) {
        Set<Long> ids = new HashSet<>();
        ArrayDeque<Long> pending = new ArrayDeque<>();
        pending.add(categoryId);
        while (!pending.isEmpty()) {
            Long id = pending.removeFirst();
            if (ids.add(id)) {
                categoryRepository.findByParentIdOrderByDisplayOrderAsc(id).stream()
                        .map(Category::getId).forEach(pending::addLast);
            }
        }
        return ids;
    }

    ProductResponse toResponse(Product p) {
        Map<String, String> specs;
        try {
            specs = objectMapper.readValue(p.getSpecsJson(), new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (Exception e) {
            specs = Map.of();
        }
        int discountPercent = 0;
        if (p.getMrp() != null && p.getMrp().compareTo(BigDecimal.ZERO) > 0) {
            discountPercent = p.getMrp().subtract(p.getPrice())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(p.getMrp(), 0, java.math.RoundingMode.HALF_UP)
                    .intValue();
        }
        List<String> imageUrls = p.getImages().stream()
                .sorted((a, b) -> a.getDisplayOrder().compareTo(b.getDisplayOrder()))
                .map(ProductImage::getUrl).toList();
        List<ProductResponse.VariantResponse> variants = p.getVariants().stream()
                .map(v -> new ProductResponse.VariantResponse(v.getId(), v.getVariantType(), v.getVariantValue(),
                        v.getPriceDelta(), v.getStock(), v.getSku()))
                .toList();

        return new ProductResponse(p.getId(), p.getName(), p.getSlug(), p.getSku(),
                p.getCategory().getId(), p.getCategory().getName(), p.getBrand(), p.getDescription(),
                p.getPrice(), p.getMrp(), discountPercent, p.getStock(), p.getRatingAverage(), p.getRatingCount(),
                p.isFeatured(), p.isActive(), specs, imageUrls, variants, p.getCreatedAt());
    }
}
