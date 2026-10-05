package com.rover.admin.agent.model;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型配置路径与主密钥来源，默认存放在工作目录 config/。
 * 主密钥优先级：显式属性、ROVER_ADMIN_MASTER_KEY、主密钥文件。
 * 主密钥文件与模型配置同目录时视为未隔离，启动时告警。
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

    /** 读取主密钥：显式属性优先，其次 {@link #MASTER_KEY_ENV}；未配置返回空串。 */
    public String resolveMasterKey() {
        if (hasText(masterKey)) {
            return masterKey.trim();
        }
        String fromEnv = System.getenv(MASTER_KEY_ENV);
        return hasText(fromEnv) ? fromEnv.trim() : "";
    }

    /** 显式提供主密钥或将密钥文件放在独立目录时返回 true。 */
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