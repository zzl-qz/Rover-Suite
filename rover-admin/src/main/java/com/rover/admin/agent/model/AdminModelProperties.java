package com.rover.admin.agent.model;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型配置的落盘位置与主密钥来源。
 *
 * 默认写到工作目录下的 {@code config/}，与 Gateway / Nameserver 的本地运行时配置放在一起，
 * 随项目一起维护与备份；整个 {@code config/} 已在 {@code .gitignore} 中，密钥不会被误提交。
 *
 * 主密钥优先级：{@code rover.admin.model.master-key}（环境变量可用
 * {@code ROVER_ADMIN_MODEL_MASTER_KEY}）→ 环境变量 {@code ROVER_ADMIN_MASTER_KEY}
 * → 主密钥文件。默认主密钥文件与模型配置文件同目录，仅适合开发环境：
 * 生产环境请用环境变量或 {@code master-key-file} 指向独立目录，
 * 否则 {@link #isMasterKeyIsolated()} 为 false，启动时会输出安全告警。
 */
@ConfigurationProperties(prefix = AdminModelProperties.PREFIX)
public class AdminModelProperties {

    public static final String PREFIX = "rover.admin.model";

    /** 默认配置目录；相对路径按进程工作目录解析。 */
    private static final String DEFAULT_DIRECTORY = "config";

    /** 生产环境推荐的主密钥环境变量；与配置属性（宽松绑定）二选一即可。 */
    public static final String MASTER_KEY_ENV = "ROVER_ADMIN_MASTER_KEY";

    /** 模型配置文件路径；留空用工作目录下 {@code config/admin-model.properties}。 */
    private String configFile = "";
    /** 主密钥（口令或 base64 32 字节）；留空则用主密钥文件。 */
    private String masterKey = "";
    /** 主密钥文件路径；留空用配置文件同级的 {@code master.key}。 */
    private String masterKeyFile = "";

    /** 配置文件绝对路径。 */
    public Path configFilePath() {
        if (hasText(configFile)) {
            return Path.of(configFile.trim()).toAbsolutePath().normalize();
        }
        return Path.of(DEFAULT_DIRECTORY, "admin-model.properties").toAbsolutePath().normalize();
    }

    /** 主密钥文件绝对路径。 */
    public Path masterKeyPath() {
        if (hasText(masterKeyFile)) {
            return Path.of(masterKeyFile.trim()).toAbsolutePath().normalize();
        }
        return configFilePath().resolveSibling("master.key");
    }

    public String getConfigFile() {
        return configFile;
    }

    public void setConfigFile(String configFile) {
        this.configFile = configFile;
    }

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }

    /**
     * 实际生效的主密钥：显式配置优先，其次环境变量 {@link #MASTER_KEY_ENV}；都没有返回空串。
     *
     * 环境变量单独支持（而不是只靠宽松绑定）是因为部署脚本里它是最常用的一种注入方式，
     * 名字必须简短好记；宽松绑定名 {@code ROVER_ADMIN_MODEL_MASTER_KEY} 同样有效。
     */
    public String resolveMasterKey() {
        if (hasText(masterKey)) {
            return masterKey.trim();
        }
        String fromEnv = System.getenv(MASTER_KEY_ENV);
        return hasText(fromEnv) ? fromEnv.trim() : "";
    }

    /**
     * 主密钥是否与模型配置文件"真正隔离"。
     *
     * 显式提供了主密钥（属性或环境变量）→ 隔离；主密钥文件被显式指到别的目录 → 隔离；
     * 默认的"同目录 {@code master.key}" → 未隔离：主密钥和密文一起被拷走时就等于没加密，
     * 这种默认值只适合开发环境，启动时会有安全告警。
     */
    public boolean isMasterKeyIsolated() {
        if (!resolveMasterKey().isEmpty()) {
            return true;
        }
        if (!hasText(masterKeyFile)) {
            return false;
        }
        Path keyDirectory = masterKeyPath().getParent();
        Path configDirectory = configFilePath().getParent();
        return keyDirectory == null || !keyDirectory.equals(configDirectory);
    }

    public String getMasterKeyFile() {
        return masterKeyFile;
    }

    public void setMasterKeyFile(String masterKeyFile) {
        this.masterKeyFile = masterKeyFile;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}