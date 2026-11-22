package com.quanxiaoha.vector.store.controller;

import com.quanxiaoha.vector.store.reader.MyHtmlReader;
import com.quanxiaoha.vector.store.reader.MyJsonReader;
import com.quanxiaoha.vector.store.reader.MyMarkdownReader;
import com.quanxiaoha.vector.store.reader.MyPdfReader;
import com.quanxiaoha.vector.store.reader.MyTikaPptReader;
import com.quanxiaoha.vector.store.reader.MyTikaWordReader;
import com.quanxiaoha.vector.store.reader.MyTextReader;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多种格式文档的读取与入库。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@RestController
@RequestMapping("/read")
public class ReaderController {

    private final MyTextReader textReader;
    private final MyJsonReader jsonReader;
    private final MyMarkdownReader markdownReader;
    private final MyHtmlReader htmlReader;
    private final MyPdfReader pdfReader;
    private final MyTikaWordReader tikaWordReader;
    private final MyTikaPptReader tikaPptReader;
    private final VectorStore vectorStore;

    public ReaderController(MyTextReader textReader, MyJsonReader jsonReader, MyMarkdownReader markdownReader,
                            MyHtmlReader htmlReader, MyPdfReader pdfReader, MyTikaWordReader tikaWordReader,
                            MyTikaPptReader tikaPptReader, VectorStore vectorStore) {
        this.textReader = textReader;
        this.jsonReader = jsonReader;
        this.markdownReader = markdownReader;
        this.htmlReader = htmlReader;
        this.pdfReader = pdfReader;
        this.tikaWordReader = tikaWordReader;
        this.tikaPptReader = tikaPptReader;
        this.vectorStore = vectorStore;
    }

    /**
     * 读取 Txt 文档（整个文件作为一条 Document）。
     */
    @GetMapping(value = "/txt")
    public List<Document> readText() {
        return textReader.loadText();
    }

    /**
     * 读取 Txt 文档并分块拆分。
     */
    @GetMapping(value = "/txt2")
    public List<Document> readText2() {
        return textReader.loadTextAndSplit();
    }

    /**
     * 读取 Json 文档。
     */
    @GetMapping(value = "/json")
    public List<Document> readJson() {
        return jsonReader.loadJson();
    }

    /**
     * 读取 Markdown 文档。
     */
    @GetMapping(value = "/md")
    public List<Document> readMarkdown() {
        return markdownReader.loadMarkdown();
    }

    /**
     * 读取 Html 文档。
     */
    @GetMapping(value = "/html")
    public List<Document> readHtml() {
        return htmlReader.loadHtml();
    }

    /**
     * 读取 Pdf 文档，每页一条。
     */
    @GetMapping(value = "/pdf")
    public List<Document> readPdf() {
        return pdfReader.getDocsFromPdf();
    }

    /**
     * 读取 Word 文档（Apache Tika 解析后分块）。
     */
    @GetMapping(value = "/word")
    public List<Document> readWord() {
        return tikaWordReader.load();
    }

    /**
     * 读取 PPT 文档（Apache Tika 解析后分块）。
     */
    @GetMapping(value = "/ppt")
    public List<Document> readPpt() {
        return tikaPptReader.load();
    }

    /**
     * 把指定格式的文档向量化后写入 Redis 向量库。
     *
     * @param type txt / json / md / html / pdf / word / ppt / all
     * @return 各格式写入的条数
     */
    @PostMapping(value = "/ingest")
    public Map<String, Integer> ingest(@RequestParam(value = "type", defaultValue = "all") String type) {
        Map<String, List<Document>> loaded = new LinkedHashMap<>();
        if (matches(type, "txt")) {
            // 长文本入库前先分块，否则单个超大向量会稀释语义
            loaded.put("txt", textReader.loadTextAndSplit());
        }
        if (matches(type, "json")) {
            loaded.put("json", jsonReader.loadJson());
        }
        if (matches(type, "md", "markdown")) {
            // sourceType 取值与各 Reader 内部保持一致，避免过滤检索时对不上
            loaded.put("markdown", markdownReader.loadMarkdown());
        }
        if (matches(type, "html")) {
            loaded.put("html", htmlReader.loadHtml());
        }
        if (matches(type, "pdf")) {
            loaded.put("pdf", pdfReader.getDocsFromPdf());
        }
        if (matches(type, "word", "docx")) {
            loaded.put("word", tikaWordReader.load());
        }
        if (matches(type, "ppt", "pptx")) {
            loaded.put("ppt", tikaPptReader.load());
        }
        if (loaded.isEmpty()) {
            throw new IllegalArgumentException(
                    "不支持的文档类型: " + type + "，可选值: txt/json/md/html/pdf/word/ppt/all");
        }

        Map<String, Integer> result = new LinkedHashMap<>();
        loaded.forEach((key, documents) -> {
            // sourceType 由各 Reader 自己写入，这里不再覆盖
            vectorStore.add(documents);
            result.put(key, documents.size());
        });
        return result;
    }

    private boolean matches(String type, String... candidates) {
        if ("all".equalsIgnoreCase(type)) {
            return true;
        }
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

}
