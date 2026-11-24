package com.lzu.marketplace.product;

import com.lzu.marketplace.product.dto.CreateProductRequest;
import com.lzu.marketplace.product.dto.ProductResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 商品业务逻辑。
 *
 * <p>当前只有发布（L0-2）。列表 / 详情 / 上下架在后续阶段补充。
 */
@Service
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    /**
     * 发布商品。新商品固定为「上架」状态（官方 §4 L0-2 示例返回 {@code "status":"on"}）。
     *
     * <p>事务说明：单条 INSERT 本身原子，这里仍显式标注，是为了后续列表/详情等
     * 多写操作加入时事务边界不被误改。
     */
    @Transactional
    public ProductResponse create(CreateProductRequest req) {
        CreateProductRequest normalized = normalize(req);
        Product saved = repository.insert(normalized, Product.STATUS_ON);
        return ProductResponse.from(saved);
    }

    /**
     * 去除首尾空白。
     *
     * <p>为什么在这做而不是让客户端负责：
     * <ul>
     *   <li>{@code "   "} 能通过 {@code @NotBlank} 的长度检查吗？不能——@NotBlank 已拦截纯空白，
     *       但 {@code "  商品  "} 会原样入库，导致列表关键字匹配与展示出现前后空格；</li>
     *   <li>描述为空串时统一存 null，避免「有值但无内容」和「没传」两种语义混在库里。</li>
     * </ul>
     * 归一化在后置校验之后执行，所以不会把非法输入"洗白"成合法。
     */
    private CreateProductRequest normalize(CreateProductRequest req) {
        String description = StringUtils.hasText(req.description()) ? req.description().trim() : null;
        return new CreateProductRequest(
                req.title().trim(),
                req.price(),
                req.stock(),
                req.category().trim(),
                description,
                req.sellerName().trim());
    }
}
