package com.quanxiaoha.vector.store.exception;

/**
 * 调用 SearXNG 失败时抛出。
 * <p>
 * 单独定义一个异常，而不是把失败悄悄变成「空结果」：
 * SearXNG 没部署好、地址写错、返回的不是 JSON —— 这些都属于故障，
 * 如果一律返回空集合，调用方分不清「没搜到」和「服务挂了」。
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
public class SearXNGException extends RuntimeException {

    public SearXNGException(String message) {
        super(message);
    }

    public SearXNGException(String message, Throwable cause) {
        super(message, cause);
    }

}
