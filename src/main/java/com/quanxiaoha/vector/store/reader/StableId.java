package com.quanxiaoha.vector.store.reader;

import org.springframework.ai.document.Document;

import java.util.Map;

/**
 * 给读取出来的文档分配**确定性 id**。
 * <p>
 * RedisVectorStore 的 Redis key = {@code prefix + document.getId()}，
 * 而默认的 {@code new Document(text, metadata)} 会用 IdGenerator 生成随机 UUID，
 * 导致每次入库都是新增一条 key（重复数据堆积）。
 * 这里把 id 固定成「来源文件 + 定位信息」，重复入库就变成对同一条 key 的覆盖写。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
public final class StableId {

    private StableId() {
    }

    /**
     * 用确定性 id 和附加元数据重建一份 Document。
     *
     * @param doc           原始文档
     * @param id            确定性 id
     * @param extraMetadata 附加元数据，会合并进原元数据
     * @return 新的 Document
     */
    public static Document of(Document doc, String id, Map<String, Object> extraMetadata) {
        Document.Builder builder = doc.mutate().id(id);
        extraMetadata.forEach(builder::metadata);
        return builder.build();
    }

}
