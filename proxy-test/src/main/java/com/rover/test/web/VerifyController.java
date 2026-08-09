package com.rover.test.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 规格自校验端点：前端把期望值编码进 X-Verify-Spec 头（base64url JSON），
 * 后端逐项断言 method/path/query/headers/cookie/body/上传文件，返回每项
 * 「期望 vs 实际 vs 是否通过」明细，作为页面 PASS/FAIL 的依据。
 *
 * Spec JSON 字段：
 *   method / path / headers{name:value} / headersPresent[name...]
 *   cookie{name,value} / bodyExact / bodyContains[...] / bodyLength
 *   file{name,filename,content}
 * query 由 URL 本身携带，逐 key 比对解码后的值。
 */
@RestController
public class VerifyController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SPEC_HEADER = "X-Verify-Spec";

    record Check(String name, String expected, String actual, boolean ok) {
    }

    @PostMapping({"/user/verify", "/api/verify"})
    public Map<String, Object> verify(
            HttpServletRequest req,
            @RequestParam(value = "report", required = false) MultipartFile file) throws IOException {

        List<Check> checks = new ArrayList<>();
        String rawSpec = req.getHeader(SPEC_HEADER);
        if (rawSpec == null || rawSpec.isBlank()) {
            checks.add(new Check(SPEC_HEADER, "非空", "缺失", false));
            return report(checks);
        }

        Map<String, Object> spec;
        try {
            spec = MAPPER.readValue(
                    new String(Base64.getUrlDecoder().decode(rawSpec), StandardCharsets.UTF_8),
                    new TypeReference<>() {
                    });
        } catch (Exception ex) {
            checks.add(new Check(SPEC_HEADER, "合法 JSON", "解析失败: " + ex.getMessage(), false));
            return report(checks);
        }

        String method = req.getMethod();
        String path = req.getRequestURI();
        Map<String, String[]> query = req.getParameterMap();
        String body = ProxyTestController.readBody(req);

        // 1. method
        String wantMethod = str(spec.get("method"));
        if (wantMethod != null) {
            checks.add(new Check("method", wantMethod, method, wantMethod.equalsIgnoreCase(method)));
        }
        // 2. path（后端视角，已剥离网关前缀）
        String wantPath = str(spec.get("path"));
        if (wantPath != null) {
            checks.add(new Check("path", wantPath, path, wantPath.equals(path)));
        }
        // 3. query
        Object qo = spec.get("query");
        if (qo instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) qo).entrySet()) {
                String key = String.valueOf(e.getKey());
                String want = str(e.getValue());
                String[] got = query.get(key);
                String gotStr = got == null || got.length == 0 ? null : got[0];
                checks.add(new Check("query[" + key + "]", want, gotStr, want != null && want.equals(gotStr)));
            }
        }
        // 4. headers 精确值
        Object ho = spec.get("headers");
        if (ho instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) ho).entrySet()) {
                String name = String.valueOf(e.getKey());
                String want = str(e.getValue());
                String got = req.getHeader(name);
                checks.add(new Check("header[" + name + "]", want, got, want != null && want.equals(got)));
            }
        }
        // 5. headers 仅存在
        Object hp = spec.get("headersPresent");
        if (hp instanceof List<?>) {
            for (Object o : (List<?>) hp) {
                String name = str(o);
                String got = req.getHeader(name);
                checks.add(new Check("header[" + name + "] 存在", "非空",
                        got == null ? "(缺失)" : got, got != null && !got.isBlank()));
            }
        }
        // 6. cookie
        Object co = spec.get("cookie");
        if (co instanceof Map<?, ?>) {
            String name = str(((Map<?, ?>) co).get("name"));
            String value = str(((Map<?, ?>) co).get("value"));
            String cookie = req.getHeader("Cookie");
            boolean ok = cookie != null && cookie.contains(name + "=" + value);
            checks.add(new Check("cookie[" + name + "]", name + "=" + value,
                    cookie == null ? "(无 Cookie)" : cookie, ok));
        }
        // 7. body 精确
        String bodyExact = str(spec.get("bodyExact"));
        if (bodyExact != null) {
            checks.add(new Check("body 精确", bodyExact, body, bodyExact.equals(body)));
        }
        // 8. body 包含
        Object bc = spec.get("bodyContains");
        if (bc instanceof List<?>) {
            for (Object o : (List<?>) bc) {
                String s = str(o);
                checks.add(new Check("body 包含: " + s, "包含", body, s != null && body.contains(s)));
            }
        }
        // 9. body 长度
        Object bl = spec.get("bodyLength");
        if (bl != null) {
            int want = ((Number) bl).intValue();
            checks.add(new Check("body 长度", String.valueOf(want),
                    String.valueOf(body.length()), body.length() == want));
        }
        // 10. 上传文件
        Object fo = spec.get("file");
        if (fo instanceof Map<?, ?>) {
            String wantName = str(((Map<?, ?>) fo).get("name"));
            String wantFilename = str(((Map<?, ?>) fo).get("filename"));
            String wantContent = str(((Map<?, ?>) fo).get("content"));
            String gotFilename = file == null ? "(无此字段)" : file.getOriginalFilename();
            String gotContent = file == null ? "(无内容)"
                    : new String(file.getBytes(), StandardCharsets.UTF_8);
            checks.add(new Check("file[" + wantName + "].filename", wantFilename, gotFilename,
                    file != null && wantFilename != null && wantFilename.equals(file.getOriginalFilename())));
            checks.add(new Check("file[" + wantName + "].content", wantContent, gotContent,
                    file != null && wantContent != null
                            && wantContent.equals(new String(file.getBytes(), StandardCharsets.UTF_8))));
        }

        return report(checks);
    }

    private static Map<String, Object> report(List<Check> checks) {
        int passed = 0;
        for (Check c : checks) {
            if (c.ok) {
                passed++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pass", passed == checks.size() && !checks.isEmpty());
        out.put("total", checks.size());
        out.put("passed", passed);
        List<Map<String, Object>> checkList = new ArrayList<>();
        for (Check c : checks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("expected", c.expected());
            m.put("actual", c.actual());
            m.put("ok", c.ok());
            checkList.add(m);
        }
        out.put("checks", checkList);
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
