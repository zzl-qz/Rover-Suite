package com.rover.admin.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 浏览器默认会要 /favicon.ico，转去现成的 SVG，避免 404 刷错误日志。 */
@Controller
public class FaviconController {

    @GetMapping("/favicon.ico")
    public String favicon() {
        return "forward:/favicon.svg";
    }
}
