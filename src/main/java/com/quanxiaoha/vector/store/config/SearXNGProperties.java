package com.quanxiaoha.vector.store.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SearXNG 搜索引擎配置项（对应 application.yaml 中的 {@code app.searxng}）。
 * <p>
 * 用配置类而不是散落在代码里的常量，是因为不同环境下 SearXNG 部署的地址、
 * 启用的搜索引擎都不一样，写死之后换台机器就得改代码重新打包。
 *
 * @param url        SearXNG 搜索接口地址，形如 {@code http://192.168.100.101:8888/search}
 * @param count      最多返回多少条搜索结果，同时也是接口返回条数的上限
 * @param engines    聚合的搜索引擎，多个用逗号分隔，需与 SearXNG 实例实际启用的引擎一致
 * @param language   搜索语言
 * @param safesearch 安全搜索级别：0-关闭 1-中等 2-严格
 * @Author: CFY
 * @create: 2026-11-23
 */
@ConfigurationProperties(prefix = "app.searxng")
public record SearXNGProperties(
        String url,
        int count,
        String engines,
        String language,
        int safesearch) {
}
