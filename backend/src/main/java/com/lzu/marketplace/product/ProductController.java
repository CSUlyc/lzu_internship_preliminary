package com.lzu.marketplace.product;

import com.lzu.marketplace.common.ApiResponse;
import com.lzu.marketplace.product.dto.CreateProductRequest;
import com.lzu.marketplace.product.dto.ProductResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品接口。当前仅实现官方 §4 L0-2 发布商品。
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    /**
     * POST /api/products —— 发布商品。
     *
     * <p>成功返回 HTTP 201 与完整商品对象（官方 §4 L0-2）。
     * 参数不合法由 {@code GlobalExceptionHandler} 统一转为 400 + 40001。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        return ApiResponse.ok(service.create(request));
    }
}
