package com.rover.admin.agent.model;

import com.rover.admin.web.AdminApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型配置接口：读取当前状态、保存并即时生效、连接测试与效果验证。
 *
 * 返回体里只有掩码 {@code ******}，不出现明文密钥也不出现密文；
 * 密钥只在 {@code POST} 的请求体里出现一次。
 */
@RestController
@RequestMapping(AdminApiPaths.PREFIX)
@Slf4j
public class AdminModelController {

    private final AdminModelConfigService modelConfigService;

    public AdminModelController(AdminModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    /** 当前模型配置与生效状态，含预设列表。 */
    @GetMapping(AdminApiPaths.MODEL_CONFIG)
    public Map<String, Object> config() {
        return modelConfigService.current();
    }

    /** 保存并立即生效（无需重启 Admin）。 */
    @PostMapping(AdminApiPaths.MODEL_CONFIG)
    public Map<String, Object> save(@RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest request, Authentication authentication) {
        log.info("模型配置变更：来源={} 操作者={}", request.getRemoteAddr(), operator(authentication));
        return modelConfigService.save(body == null ? Map.of() : body);
    }

    /** 连接测试：只验证候选值能不能连通，不落盘。 */
    @PostMapping(AdminApiPaths.MODEL_TEST)
    public Map<String, Object> test(@RequestBody(required = false) Map<String, Object> body) {
        return modelConfigService.test(body == null ? Map.of() : body);
    }

    /** 效果验证：对当前已生效的配置再跑一次。 */
    @PostMapping(AdminApiPaths.MODEL_VERIFY)
    public Map<String, Object> verify() {
        return modelConfigService.verify();
    }

    private static String operator(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return "(未启用登录)";
        }
        return authentication.getName();
    }
}