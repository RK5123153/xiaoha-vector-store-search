package com.quanxiaoha.vector.store.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.JsonReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Json 文件读取器。
 * <p>
 * {@link JsonReader} 会把 Json 数组中的每个元素转换成一条 Document，
 * 指定的 key 会被拼接成 Document 的正文，其余字段进入元数据。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@Component
public class MyJsonReader {

    private static final String FILENAME = "tv.json";

    private final Resource resource;

    public MyJsonReader(@Value("classpath:/document/" + FILENAME) Resource resource) {
        this.resource = resource;
    }

    /**
     * 读取 Json 文件。
     */
    public List<Document> loadJson() {
        // 创建 JsonReader 阅读器实例，配置需要读取的字段
        JsonReader jsonReader = new JsonReader(resource, "description", "content", "title");
        List<Document> documents = jsonReader.get();
        documents.forEach(doc -> {
            doc.getMetadata().put("sourceType", "json");
            doc.getMetadata().put("filename", FILENAME);
        });
        return documents;
    }

}
