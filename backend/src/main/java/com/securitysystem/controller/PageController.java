package com.securitysystem.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/login")
    public String login() { return "forward:/login.html"; }

    @GetMapping("/dashboard")
    public String dashboard() { return "forward:/dashboard.html"; }

    @GetMapping("/sensors")
    public String sensors() { return "forward:/sensors.html"; }

    @GetMapping("/events")
    public String events() { return "forward:/events.html"; }
}
