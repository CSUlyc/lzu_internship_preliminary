package com.lzu.marketplace.order;

import com.lzu.marketplace.product.Product;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 下单相关的持久层。对 products 只做两件下单需要的事：加锁读取、条件扣减。
 */
@Repository
public class OrderRepository {

    private static final String ORDER_COLUMNS =
            "id, order_no, product_id, product_title, quantity, unit_price, total_amount, " +
            "buyer_name, buyer_phone, address, status, created_at, paid_at";

    private static final RowMapper<Order> ORDER_ROW_MAPPER = (rs, rowNum) -> new Order(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getLong("product_id"),
            rs.getString("product_title"),
            rs.getInt("quantity"),
            rs.getLong("unit_price"),
            rs.getLong("total_amount"),
            rs.getString("buyer_name"),
            rs.getString("buyer_phone"),
            rs.getString("address"),
            rs.getString("status"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("paid_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 在同一事务内对商品行加排他锁读取（{@code SELECT ... FOR UPDATE}）。
     *
     * <p>锁持有到事务提交/回滚。之后的校验、扣减、订单插入都发生在锁内，
     * 并发下单会在此处排队，杜绝「两个请求都读到 stock=1 然后都扣成 0」。
     *
     * <p>用 {@code query() + findFirst()} 而不是 {@code queryForObject}：
     * 后者在 0 行时抛 EmptyResultDataAccessException，让「商品不存在」变成异常路径。
     *
     * @return 商品不存在时为 empty
     */
    public Optional<Product> findByIdForUpdate(long productId) {
        return jdbc.query(
                        "SELECT id, title, price, stock, category, description, status, " +
                        "seller_name, created_at FROM products WHERE id = ? FOR UPDATE",
                        (rs, rowNum) -> new Product(
                                rs.getLong("id"),
                                rs.getString("title"),
                                rs.getLong("price"),
                                rs.getInt("stock"),
                                rs.getString("category"),
                                rs.getString("description"),
                                rs.getString("status"),
                                rs.getString("seller_name"),
                                rs.getObject("created_at", LocalDateTime.class)),
                        productId)
                .stream()
                .findFirst();
    }

    /**
     * 扣减库存，返回受影响行数。
     *
     * <p>WHERE 带上 {@code stock >= ?} 作为第二道防线：锁内读到的库存与扣减之间
     * 理论上不会有其他写入，但条件扣减让这条 UPDATE 本身也不会把库存扣成负数——
     * 调用方必须检查返回值（题目 L1-6 第 8 条）。
     *
     * @return 0 表示库存不足，未扣减
     */
    public int deductStock(long productId, int quantity) {
        return jdbc.update(
                "UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?",
                quantity, productId, quantity);
    }

    /**
     * 插入订单并回填 id / created_at。
     */
    public Order insert(String orderNo, long productId, String productTitle, int quantity,
                        long unitPrice, long totalAmount, String buyerName, String buyerPhone,
                        String address) {
        String sql = "INSERT INTO orders (order_no, product_id, product_title, quantity, unit_price, " +
                     "total_amount, buyer_name, buyer_phone, address) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, orderNo);
            ps.setLong(2, productId);
            ps.setString(3, productTitle);
            ps.setInt(4, quantity);
            ps.setLong(5, unitPrice);
            ps.setLong(6, totalAmount);
            ps.setString(7, buyerName);
            ps.setString(8, buyerPhone);
            ps.setString(9, address);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入订单后未取得自增主键");
        }
        return findById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("插入订单后无法回读记录 id=" + key));
    }

    public Optional<Order> findById(long id) {
        return jdbc.query(
                        "SELECT " + ORDER_COLUMNS + " FROM orders WHERE id = ?",
                        ORDER_ROW_MAPPER, id)
                .stream()
                .findFirst();
    }
}
