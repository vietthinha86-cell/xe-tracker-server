package com.xetheodoi.xe_tracker_server;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/tai")
    public String downloadPage() {
        return "forward:/tai.html";
    }

    @GetMapping("/admin")
    public String adminPage() {
        return "forward:/admin.html";
    }
}

