package com.gadgetly.service;

import com.gadgetly.dto.ProductRequest;
import com.gadgetly.dto.ProductResponse;
import com.gadgetly.entity.Category;
import com.gadgetly.exception.ApiException;
import com.gadgetly.repository.CategoryRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class ProductServiceIntegrationTest {

    @Autowired private ProductService products;
    @Autowired private CategoryRepository categories;
    @Autowired private EntityManager entityManager;

    @Test
    void parentCategorySearchIncludesChildrenAndHiddenProductsRemainAdminEditable() {
        Category parent = category("Televisions test", "test-televisions", null);
        Category child = category("Smart TVs test", "test-smart-tvs", parent);
        ProductResponse created = products.create(request("TEST-TV-1", child.getId(), true, null));

        assertEquals(1, products.search(null, parent.getId(), null, null, null, null, null,
                PageRequest.of(0, 10)).getTotalElements());

        products.delete(created.id());

        assertEquals(0, products.search(null, parent.getId(), null, null, null, null, null,
                PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, products.listForAdmin(PageRequest.of(0, 10)).getTotalElements());
        ApiException hidden = assertThrows(ApiException.class, () -> products.getBySlug(created.slug()));
        assertEquals(HttpStatus.NOT_FOUND, hidden.getStatus());
        assertEquals(false, products.listForAdmin(PageRequest.of(0, 10)).getContent().get(0).active());
    }

    @Test
    void editingProductWithoutVariantsPreservesExistingVariant() {
        Category category = category("Audio test", "test-audio", null);
        List<ProductRequest.VariantRequest> variants = List.of(new ProductRequest.VariantRequest(
                "Color", "Black", BigDecimal.ZERO, 3, "TEST-AUDIO-BLACK"));
        ProductResponse created = products.create(request("TEST-AUDIO-1", category.getId(), true, variants));
        entityManager.flush();
        entityManager.clear();
        Long variantId = products.getById(created.id()).variants().get(0).id();

        products.update(created.id(), request("TEST-AUDIO-1", category.getId(), true, null));
        entityManager.flush();
        entityManager.clear();
        ProductResponse updated = products.getById(created.id());

        assertEquals(1, updated.variants().size());
        assertEquals(variantId, updated.variants().get(0).id());
    }

    @Test
    void rejectsVariantDiscountThatWouldMakePriceNegative() {
        Category category = category("Kitchen test", "test-kitchen", null);
        List<ProductRequest.VariantRequest> variants = List.of(new ProductRequest.VariantRequest(
                "Size", "Large", BigDecimal.valueOf(-101), 1, "TEST-KITCHEN-LARGE"));

        ApiException error = assertThrows(ApiException.class,
                () -> products.create(request("TEST-KITCHEN-1", category.getId(), true, variants)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    private Category category(String name, String slug, Category parent) {
        Category category = new Category();
        category.setName(name);
        category.setSlug(slug);
        category.setParent(parent);
        return categories.save(category);
    }

    private ProductRequest request(String sku, Long categoryId, boolean active,
                                   List<ProductRequest.VariantRequest> variants) {
        return new ProductRequest("Demo product", categoryId, "Demo", "Test product", sku,
                BigDecimal.valueOf(100), BigDecimal.valueOf(120), 5, Map.of(), false, active,
                List.of(), variants);
    }
}
