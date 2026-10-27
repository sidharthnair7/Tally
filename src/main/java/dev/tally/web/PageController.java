package dev.tally.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** The landing page is index.html; the audit itself lives at /workspace. */
@Controller
public class PageController {

    @GetMapping("/workspace")
    public String workspace() {
        return "forward:/workspace.html";
    }
}
