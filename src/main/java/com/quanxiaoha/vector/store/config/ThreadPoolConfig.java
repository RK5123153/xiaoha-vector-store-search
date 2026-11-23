package com.quanxiaoha.vector.store.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 自定义线程池。
 * <p>
 * 联网搜索要并发访问几十个网页，把任务按「IO 密集」和「CPU 密集」分到两个池子里：
 * <ul>
 *   <li>{@code httpRequestExecutor} —— 发 HTTP 请求。任务绝大部分时间在等网络响应，
 *       线程是闲着的，所以线程数给得多，吞吐才上得去；</li>
 *   <li>{@code resultProcessingExecutor} —— 汇总/加工结果。这部分是纯 CPU 计算，
 *       线程数跟 CPU 核数挂钩，线程太多只会加剧上下文切换。</li>
 * </ul>
 * 两个池子分开还有个好处：抓网页把 IO 线程占满时，结果汇总那一步依然能跑得动。
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
@Configuration
public class ThreadPoolConfig {

    /**
     * HTTP 请求线程池（IO 密集型任务）。
     * <p>
     * 拒绝策略用 {@link ThreadPoolExecutor.CallerRunsPolicy}：
     * 池子满了就让调用线程自己跑这个任务，调用方天然被压慢，
     * 相当于给上游做了一次背压，好过直接丢任务。
     * <p>
     * <b>关于这三个数字</b>：ThreadPoolExecutor 的扩容顺序是
     * 「线程数没到 core → 新建线程；到 core 了 → 先进队列；<b>队列满了</b> → 才建线程直到 max」。
     * 也就是说队列容量 1000 的话，max=200 几乎轮不到（要堆满 1000 个任务才会触发）。
     * 想让线程数真的能涨上去，队列要配小一点；抓网页这种场景任务量不大，
     * core=50 就够用了。
     *
     * @return 线程池
     */
    @Bean("httpRequestExecutor")
    public ThreadPoolTaskExecutor httpRequestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(50); // 核心线程数（保持常驻）
        executor.setMaxPoolSize(200); // 最大线程数（队列满了之后才用得上）
        executor.setQueueCapacity(1000); // 任务队列容量（缓冲突发请求）
        executor.setKeepAliveSeconds(120); // 空闲线程存活时间（秒）
        executor.setThreadNamePrefix("http-fetcher-"); // 线程名前缀（便于监控）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 不用手动 initialize()：Spring 容器会调用 afterPropertiesSet() 完成初始化
        return executor;
    }

    /**
     * 结果处理线程池（CPU 密集型任务）。
     * <p>
     * 拒绝策略用 {@link ThreadPoolExecutor.AbortPolicy}：队列刻意开小，
     * 任务堆积说明下游已经处理不过来了，此时快速失败比默默排队更诚实。
     *
     * @return 线程池
     */
    @Bean("resultProcessingExecutor")
    public ThreadPoolTaskExecutor resultProcessingExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(cores); // 核心线程数（等于 CPU 核心数）
        executor.setMaxPoolSize(cores * 2); // 最大线程数（不超过 CPU 核心数 2 倍）
        executor.setQueueCapacity(200); // 较小队列（避免任务堆积）
        executor.setThreadNamePrefix("result-processor-"); // 线程名前缀（便于监控）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

}
