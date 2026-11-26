package com.gadgetly.service;

import com.gadgetly.dto.DashboardStatsResponse;
import com.gadgetly.entity.Order;
import com.gadgetly.entity.Product;
import com.gadgetly.repository.OrderRepository;
import com.gadgetly.repository.ProductRepository;
import com.gadgetly.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;

    private static final int LOW_STOCK_THRESHOLD = 5;

    public DashboardStatsResponse getStats() {
        List<Order> allOrders = orderRepository.findAll();
        BigDecimal totalRevenue = allOrders.stream()
                .filter(o -> o.getStatus() != Order.Status.CANCELLED)
                .map(Order::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long totalOrders = allOrders.size();
        long totalCustomers = userRepository.count();

        List<Product> lowStock = productRepository.findAll().stream()
                .filter(p -> p.getStock() <= LOW_STOCK_THRESHOLD)
                .toList();

        List<DashboardStatsResponse.LowStockItem> items = lowStock.stream()
                .map(p -> new DashboardStatsResponse.LowStockItem(p.getId(), p.getName(), p.getStock()))
                .toList();

        return new DashboardStatsResponse(totalRevenue, totalOrders, totalCustomers, lowStock.size(), items);
    }
}
