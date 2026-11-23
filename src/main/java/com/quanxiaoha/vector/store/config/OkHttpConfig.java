package com.quanxiaoha.vector.store.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * OkHttp 客户端配置。
 * <p>
 * 联网搜索（调用 SearXNG）走的是 OkHttp 而不是 JDK 自带的 HttpClient，
 * 主要是为了连接池与超时可控：SearXNG 会聚合多个搜索引擎，单次请求天然偏慢，
 * 连接池能避免每次搜索都重新握手 TCP/TLS。
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
@Configuration
public class OkHttpConfig {

    /**
     * 构建全局共享的 OkHttpClient。
     * <p>
     * OkHttpClient 内部维护连接池与线程池，创建代价高且线程安全，
     * 所以整个应用只创建这一个实例，不要在业务代码里 new。
     *
     * @param connectTimeout    建立连接的最大等待时间（毫秒）
     * @param readTimeout       读取数据的最大等待时间（毫秒）
     * @param writeTimeout      写入数据的最大等待时间（毫秒）
     * @param maxIdleConnections 连接池中保持的最大空闲连接数
     * @param keepAliveDuration 空闲连接在连接池中的存活时间（分钟）
     * @return OkHttpClient
     */
    @Bean
    public OkHttpClient okHttpClient(
            @Value("${app.okhttp.connect-timeout:5000}") int connectTimeout,
            @Value("${app.okhttp.read-timeout:30000}") int readTimeout,
            @Value("${app.okhttp.write-timeout:15000}") int writeTimeout,
            @Value("${app.okhttp.max-idle-connections:200}") int maxIdleConnections,
            @Value("${app.okhttp.keep-alive-duration:5}") int keepAliveDuration) {

        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .writeTimeout(writeTimeout, TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool(maxIdleConnections, keepAliveDuration, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true) // 连接被对端断开时自动重试一次，避免偶发失败
                .build();
    }

}
