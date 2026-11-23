package com.quanxiaoha.vector.store.util;

import okhttp3.Dns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * 带重试与兜底的 DNS 解析器。
 * <p>
 * <b>为什么需要它</b>：JDK 会把「域名解析失败」这个结果缓存 10 秒
 * （系统属性 {@code networkaddress.cache.negative.ttl} 的默认值）。
 * 而本地 DNS 服务器在并发查询（比如一次并发抓 10 个网页）时偶尔会超时，
 * 一旦某个域名解析失败，接下来 10 秒内所有指向它的请求都会立刻失败，
 * <b>此时直接重试是没有用的</b> —— 拿到的仍是缓存里的失败结果。
 * <p>
 * <b>怎么办</b>：先用系统解析（走缓存，快）；失败后改用 JNDI 的 DNS provider
 * 直接发一次 DNS 查询（这条路不经过 InetAddress 的缓存），
 * 再配合几次重试，把「DNS 服务器抽风」这种偶发故障消化掉。
 *
 * @Author: CFY
 * @create: 2026-11-23
 */
public class ResilientDns implements Dns {

    private static final Logger log = LoggerFactory.getLogger(ResilientDns.class);

    /**
     * 最多尝试几轮（每轮 = 系统解析 + JNDI 查询）。
     */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * 每轮之间的等待时间（毫秒），给本地 DNS 服务器喘息的机会。
     */
    private static final long RETRY_DELAY_MILLIS = 300;

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        UnknownHostException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
                if (!addresses.isEmpty()) {
                    return addresses;
                }
            } catch (UnknownHostException e) {
                lastError = e;
            }

            // 系统解析失败：换条路，绕开 InetAddress 的负缓存重新查一次
            try {
                List<InetAddress> addresses = lookupByJndi(hostname);
                if (!addresses.isEmpty()) {
                    return addresses;
                }
            } catch (Exception e) {
                lastError = new UnknownHostException(hostname + "（DNS 查询失败：" + e.getMessage() + "）");
            }
            sleep(RETRY_DELAY_MILLIS * attempt);
        }
        log.warn("## DNS 解析失败 {}，已重试 {} 轮", hostname, MAX_ATTEMPTS);
        throw lastError != null ? lastError : new UnknownHostException(hostname);
    }

    /**
     * 用 JNDI 的 DNS provider 直接查询，走的是另一条解析路径，不受 InetAddress 缓存影响。
     */
    private List<InetAddress> lookupByJndi(String hostname) throws Exception {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(ContextKeys.FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put(ContextKeys.PROVIDER_URL, "dns:///");

        DirContext context = null;
        try {
            context = new InitialDirContext(env);
            for (String record : new String[]{"A", "AAAA"}) {
                Attributes attributes = context.getAttributes(hostname, new String[]{record});
                Attribute attribute = attributes.get(record);
                if (attribute == null) {
                    continue;
                }
                List<InetAddress> addresses = new ArrayList<>();
                NamingEnumeration<?> values = attribute.getAll();
                while (values.hasMore()) {
                    // 拿到的是 IP 字符串字面量，不会再触发一次 DNS 查询
                    addresses.add(InetAddress.getByName(values.next().toString()));
                }
                if (!addresses.isEmpty()) {
                    return addresses;
                }
            }
            return List.of();
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响主流程
                }
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * JNDI 初始化的环境变量名。
     */
    private static final class ContextKeys {
        static final String FACTORY = "java.naming.factory.initial";
        static final String PROVIDER_URL = "java.naming.provider.url";
    }

}
