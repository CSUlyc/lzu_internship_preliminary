package com.lzu.marketplace.common;

/**
 * 官方业务码表（见 backend/README.md §3）。
 *
 * <p>HTTP 状态码与业务码的对应关系由各接口显式指定，本类只收敛业务码常量，
 * 避免散落的魔法数字。
 */
public final class ApiCode {

    /** 成功，HTTP 200 / 201 */
    public static final int SUCCESS = 0;

    /** 参数不对（缺字段、类型错、超范围、枚举非法），HTTP 400 */
    public static final int BAD_REQUEST = 40001;

    /** 商品 / 订单不存在，HTTP 404 */
    public static final int NOT_FOUND = 40401;

    /** 库存不足，HTTP 409 */
    public static final int STOCK_INSUFFICIENT = 40901;

    /** 商品已下架，HTTP 409 */
    public static final int PRODUCT_OFF_SHELF = 40902;

    /** 订单已支付，不可重复支付，HTTP 409 */
    public static final int ORDER_ALREADY_PAID = 40903;

    /** 服务出错，HTTP 500（不把堆栈返回给客户端） */
    public static final int SERVER_ERROR = 50000;

    private ApiCode() {
    }
}
