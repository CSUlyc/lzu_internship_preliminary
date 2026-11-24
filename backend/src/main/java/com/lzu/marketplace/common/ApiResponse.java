package com.lzu.marketplace.common;

/**
 * 统一响应外层，对应官方接口约定 {@code {code, message, data}}。
 *
 * <p>业务码见 {@link ApiCode}。所有接口无论成功失败都使用该结构。
 *
 * @param code    业务码，0 表示成功
 * @param message 提示文案
 * @param data    业务数据，无数据时为 {@code null}
 * @param <T>     业务数据类型
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ApiCode.SUCCESS, "ok", data);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
