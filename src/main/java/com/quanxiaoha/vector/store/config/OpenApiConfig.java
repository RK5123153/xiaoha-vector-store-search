package com.quanxiaoha.vector.store.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI（Swagger）文档配置。
 * <p>
 * 启动后可以访问：
 * <ul>
 *   <li>http://localhost:8090/swagger-ui.html —— Swagger UI 交互页面</li>
 *   <li>http://localhost:8090/v3/api-docs —— OpenAPI JSON 描述文件</li>
 * </ul>
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI xiaohaVectorStoreOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("小哈向量存储与检索 API")
                        .description("""
                                基于 Spring AI 的向量存储与检索示例。

                                **模块说明**

                                | 分组 | 说明 |
                                | --- | --- |
                                | 文档读取 `/read` | 把 txt / json / markdown / html / pdf / word / ppt 解析成 Document |
                                | 向量检索 `/vector` | 语义检索 Redis 向量库，支持按来源格式过滤 |
                                | RAG 问答 `/rag` | 先检索向量库再让大模型基于资料作答，流式 / 非流式 |
                                | 联网搜索 `/network` | 调用 SearXNG 聚合引擎，获取各大搜索引擎去重后的结果 |
                                """)
                        .version("1.0.0")
                        .contact(new Contact().name("犬小哈"))
                        .license(new License().name("Apache 2.0")));
    }

}
