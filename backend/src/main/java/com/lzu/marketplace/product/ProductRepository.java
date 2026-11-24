package com.lzu.marketplace.product;

import com.lzu.marketplace.product.dto.CreateProductRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * products 表的持久层（JdbcTemplate，无 ORM）。
 *
 * <p>所有 SQL 一律使用 {@code ?} 参数绑定，不做字符串拼接，避免 SQL 注入。
 */
@Repository
public class ProductRepository {

    private static final String COLUMNS =
            "id, title, price, stock, category, description, status, seller_name, created_at";

    /** 结果集 → 领域对象。snake_case 列名到 camelCase 字段的映射集中在这一处。 */
    private static final RowMapper<Product> ROW_MAPPER = (rs, rowNum) -> new Product(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getLong("price"),
            rs.getInt("stock"),
            rs.getString("category"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("seller_name"),
            rs.getObject("created_at", java.time.LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一条商品并回填数据库生成的 id 与 created_at。
     *
     * <p>{@code status} 由服务层决定（新商品固定 on），不从请求体接收。
     *
     * @return 含 id / created_at 的完整商品
     */
    public Product insert(CreateProductRequest req, String status) {
        String sql = "INSERT INTO products (title, price, stock, category, description, status, seller_name) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?)";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // 显式列出要回填的列，只取 id。
            // 若用 RETURN_GENERATED_KEYS，H2 会把带 DEFAULT 的 created_at 也一并返回，
            // KeyHolder 中就有多个键，getKey() 会抛 InvalidDataAccessApiUsageException。
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, req.title());
            ps.setLong(2, req.price());
            ps.setInt(3, req.stock());
            ps.setString(4, req.category());
            ps.setString(5, req.description());
            ps.setString(6, status);
            ps.setString(7, req.sellerName());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入商品后未取得自增主键");
        }
        return findById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("插入商品后无法回读记录 id=" + key));
    }

    public Optional<Product> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM products WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }
}
