package com.quanxiaoha.vector.store.reader;

import org.springframework.ai.document.Document;

import java.util.Map;

/**
 * Document 加工工具。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
public final class Documents {

    private Documents() {
    }

    /**
     * 用确定性 id 和附加元数据重建一份 Document。
     * <p>
     * RedisVectorStore 的 Redis key = {@code prefix + document.getId()}，
     * 而默认的 {@code new Document(text, metadata)} 会用 IdGenerator 生成随机 UUID，
     * 导致每次入库都是新增一条 key（重复数据堆积）。
     * 这里把 id 固定成「来源文件 + 定位信息」，重复入库就变成对同一条 key 的覆盖写。
     *
     * @param doc           原始文档
     * @param id            确定性 id
     * @param extraMetadata 附加元数据，会合并进原元数据
     * @return 新的 Document
     */
    public static Document withId(Document doc, String id, Map<String, Object> extraMetadata) {
        Document.Builder builder = doc.mutate().id(id);
        extraMetadata.forEach(builder::metadata);
        return builder.build();
    }

    /**
     * 在正文前面拼上来源标题。
     * <p>
     * 向量库检索的是文档正文，**文件名 / 标题不参与向量计算**，
     * 所以用户按文档名提问（例如问「家用空调产品说明书」，而 PPT 正文里写的是
     * 「云感变频空调 产品说明书」）时，标题对不上的话模型会判定「未找到相关资料」。
     * 把标题拼进正文后，每个分块都带上标题：既能被标题检索命中，
     * 也能让模型在上下文里看到文档的真实名称，从而把用户的说法对上号。
     *
     * @param doc   原始文档
     * @param title 来源标题（一般是去掉扩展名的文件名）
     * @return 新的 Document
     */
    public static Document withTitle(Document doc, String title) {
        String prefix = "文档标题：" + title + "\n";
        if (doc.getText() != null && doc.getText().startsWith(prefix)) {
            return doc;
        }
        return doc.mutate().text(prefix + (doc.getText() == null ? "" : doc.getText())).build();
    }

}
