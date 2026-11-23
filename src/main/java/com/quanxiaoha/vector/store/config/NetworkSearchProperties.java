package com.quanxiaoha.vector.store.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 联网搜索增强参数（对应 application.yaml 中的 {@code app.network}）。
 * <p>
 * 这些参数决定了「拿多少网页、抓多快、每页留多少字」，
 * 三者直接决定最终提示词的大小 —— 页面越多、每页越长，模型要读的上下文就越长。
 *
 * @param maxPages      最多抓取多少个网页正文作为上下文（按相关性评分从高到低取）
 * @param concurrency   并发抓取网页的线程数
 * @param contentLength 每个网页最多保留多少字符正文
 * @param fetchTimeout  单个网页抓取的超时时间（秒）
 * @param maxContextLength 所有网页正文拼起来后的最大字符数，防止提示词超出模型上下文窗口
 * @Author: CFY
 * @create: 2026-11-23
 */
@ConfigurationProperties(prefix = "app.network")
public record NetworkSearchProperties(
        int maxPages,
        int concurrency,
        int contentLength,
        int fetchTimeout,
        int maxContextLength) {
}
