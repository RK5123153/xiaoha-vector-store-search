package com.quanxiaoha.vector.store.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.RedisClient;

import java.util.List;

/**
 * 覆盖 Spring AI 自动装配的 {@link VectorStore}，补充元数据字段的索引与回显。
 * <p>
 * 自动装配出来的 RedisVectorStore 没有声明任何 metadataFields，
 * 导致 {@code similaritySearch} 返回的结果里只剩 vector_score / distance，
 * 原始元数据（来源文件、来源类型）拿不回来，也没法按来源做过滤检索。
 * 这里把这些字段声明为 TEXT 属性，既能被索引（支持 filterExpression 过滤），
 * 也会随检索结果一起返回。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@Configuration
public class RedisVectorStoreConfig {

    /**
     * 需要索引并回显的元数据字段。
     * <p>
     * 字段名必须是 ASCII 标识符：RediSearch 的 JSONPath 不支持中文属性名，
     * 否则建索引时会报 {@code Invalid JSONPath}。中文元数据（如 章节、版本）
     * 依然会被完整写进 Redis 的 JSON 里，只是不参与索引，也就无法用于过滤检索。
     */
    private static final List<String> METADATA_FIELDS = List.of(
            "sourceType", // 来源格式：txt / json / markdown / html / pdf
            "filename", // 文件名
            "source"); // 来源

    /**
     * 返回类型必须是具体的 {@link RedisVectorStore}：
     * 自动装配上的 {@code @ConditionalOnMissingBean} 是按方法返回类型推断的，
     * 若这里声明成 {@link VectorStore} 接口，条件就不会命中，会出现同名 Bean 冲突。
     */
    @Bean
    public RedisVectorStore vectorStore(JedisConnectionFactory jedisConnectionFactory,
                                        EmbeddingModel embeddingModel,
                                        RedisVectorStoreProperties properties,
                                        ObjectProvider<ObservationRegistry> observationRegistry,
                                        ObjectProvider<BatchingStrategy> batchingStrategy) {
        RedisClient jedisClient = jedisClient(jedisConnectionFactory);
        RedisVectorStore.Builder builder = RedisVectorStore.builder(jedisClient, embeddingModel)
                .indexName(properties.getIndexName())
                .prefix(properties.getPrefix())
                .initializeSchema(properties.isInitializeSchema())
                .metadataFields(METADATA_FIELDS.stream()
                        .map(RedisVectorStore.MetadataField::text)
                        .toList());

        ObservationRegistry registry = observationRegistry.getIfUnique();
        if (registry != null) {
            builder.observationRegistry(registry);
        }
        builder.batchingStrategy(batchingStrategy.getIfAvailable(TokenCountBatchingStrategy::new));

        return builder.build();
    }

    /**
     * 构造 Jedis 客户端。自动装配里的同名方法不是 Bean，只能自己复制一份。
     */
    private RedisClient jedisClient(JedisConnectionFactory connectionFactory) {
        JedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .ssl(connectionFactory.isUseSsl())
                .clientName(connectionFactory.getClientName())
                .timeoutMillis(connectionFactory.getTimeout())
                .password(connectionFactory.getPassword())
                .build();
        return RedisClient.builder()
                .hostAndPort(connectionFactory.getHostName(), connectionFactory.getPort())
                .clientConfig(clientConfig)
                .build();
    }

}
