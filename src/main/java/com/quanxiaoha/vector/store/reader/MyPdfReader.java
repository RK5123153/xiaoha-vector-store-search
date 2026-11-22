package com.quanxiaoha.vector.store.reader;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PDF 文件读取器。
 * <p>
 * {@link PagePdfDocumentReader} 依赖 PDFBox 抽取文字，{@code pagesPerDocument} 决定几页合成一条 Document。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@Component
public class MyPdfReader {

    private static final String FILENAME = "BelieveInYou.pdf";

    private static final String FILE_PATH = "classpath:/document/" + FILENAME;

    /**
     * 读取 PDF 文件，每一页单独转换为一个 Document。
     */
    public List<Document> getDocsFromPdf() {
        // 新建 PagePdfDocumentReader 阅读器
        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(FILE_PATH,
                PdfDocumentReaderConfig.builder()
                        .withPageTopMargin(0) // 设置页面顶边距为 0
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                                .withNumberOfTopTextLinesToDelete(0) // 设置删除顶部文本行数为 0
                                .withLeftAlignment(true) // 去掉每行左侧的空白，PDF 是绝对定位排版，不去掉会带大量空格噪声
                                .build())
                        .withPagesPerDocument(1) // 设置每个文档包含 1 页
                        .build());

        // 读取并转换为 Document 文档集合
        List<Document> documents = pdfReader.read();
        documents.forEach(doc -> {
            doc.getMetadata().put("sourceType", "pdf");
            doc.getMetadata().put("filename", FILENAME);
        });
        return documents;
    }

}
