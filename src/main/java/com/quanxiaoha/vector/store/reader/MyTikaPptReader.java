package com.quanxiaoha.vector.store.reader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * PPT 文件读取器（基于 Apache Tika）。
 *
 * @Author: CFY
 * @create: 2026-11-22
 */
@Component
public class MyTikaPptReader extends AbstractTikaReader {

    private static final String FILENAME = "家用空调产品说明书.pptx";

    private final Resource resource;

    public MyTikaPptReader(@Value("classpath:/document/" + FILENAME) Resource resource) {
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
        return "ppt";
    }

    @Override
    protected String title() {
        return "家用空调产品说明书";
    }

}
