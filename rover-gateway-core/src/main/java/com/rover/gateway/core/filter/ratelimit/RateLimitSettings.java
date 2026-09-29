package com.rover.gateway.core.filter.ratelimit;

import java.util.Locale;
import lombok.Data;

/**
 * Gateway 内置本地限流配置。默认关闭，避免改变现有部署行为。
 *
 * <p>字段是 {@code volatile} 的：这份配置支持运行时热更新，写线程与转发线程不是同一个。
 * 存取方法交给 Lombok，只把「需要归一化」的两个 setter 留在明处——
 * 算法名与限流维度来自配置文本，大小写和空白不该影响匹配，这一段逻辑必须看得见。
 */
@Data
public class RateLimitSettings {

    public static final String TOKEN_BUCKET = "token_bucket";
    public static final String SLIDING_WINDOW = "sliding_window";
    public static final String GLOBAL = "global";
    public static final String PATH = "path";

    private volatile boolean enabled;
    private volatile String algorithm = TOKEN_BUCKET;
    private volatile String key = PATH;
    private volatile long permitsPerSecond = 1000;
    private volatile long burst = 2000;
    private volatile long limit = 1000;
    private volatile int windowSeconds = 1;

    /** 算法名归一化：空白与大小写不参与匹配，空值回落到令牌桶。 */
    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm == null || algorithm.isBlank()
                ? TOKEN_BUCKET : algorithm.trim().toLowerCase(Locale.ROOT);
    }

    /** 限流维度归一化：同上，空值回落到按路径。 */
    public void setKey(String key) {
        this.key = key == null || key.isBlank()
                ? PATH : key.trim().toLowerCase(Locale.ROOT);
    }
}
