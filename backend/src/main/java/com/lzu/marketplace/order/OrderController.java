package com.lzu.marketplace.order;

import com.lzu.marketplace.common.ApiResponse;
import com.lzu.marketplace.order.dto.CreateOrderRequest;
import com.lzu.marketplace.order.dto.OrderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单接口。已实现官方 §5 L1-6 下单。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    /**
     * POST /api/orders —— 下单。
     *
     * <p>成功 201 + 完整订单对象；参数非法 → 400 + 40001；
     * 商品不存在 → 404 + 40401；下架 → 409 + 40902；库存不足 → 409 + 40901。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(service.create(request));
    }
}
