package com.quanxiaoha.vector.store.reader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Word 文件读取器（基于 Apache Tika）。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@Component
public class MyTikaWordReader extends AbstractTikaReader {

    private static final String FILENAME = "星海电器售后服务手册.docx";

    private final Resource resource;

    public MyTikaWordReader(@Value("classpath:/document/" + FILENAME) Resource resource) {
        this.resource = resource;
    }

    @Override
    protected Resource resource() {
        return resource;
    }

    @Override
    protected String filename() {
        return FILENAME;
    }

    @Override
    protected String sourceType() {
        return "word";
    }

}
