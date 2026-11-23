package com.quanxiaoha.vector.store.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 抓取网页正文用的线程池。
 * <p>
 * 单独抽一个线程池，而不是用 {@code ForkJoinPool.commonPool()}：
 * 抓网页是阻塞式 IO，如果把公共池占满了，整个应用里其它用 commonPool 的地方都会卡住。
 * 线程数固定，天然起到限流作用 —— 一次搜索最多同时打出去 N 个请求，
 * 既不会把目标站点打挂，也不会把本地连接池和文件句柄撑爆。
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
@Configuration
public class NetworkSearchExecutorConfig {

    /**
     * 抓取网页用的固定线程池。
     *
     * @param concurrency 并发线程数
     * @return ExecutorService
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService networkSearchExecutor(@Value("${app.network.concurrency:8}") int concurrency) {
        AtomicInteger counter = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "web-fetch-" + counter.getAndIncrement());
            thread.setDaemon(true); // 守护线程：应用关闭时不至于卡住
            return thread;
        };
        return Executors.newFixedThreadPool(Math.max(1, concurrency), threadFactory);
    }

}
