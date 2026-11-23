package com.quanxiaoha.vector.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan // 扫描 @ConfigurationProperties 配置类（如 SearXNGProperties）
public class XiaohaVectorStoreSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(XiaohaVectorStoreSearchApplication.class, args);
    }

}
