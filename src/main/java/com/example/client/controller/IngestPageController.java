package com.example.client.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class IngestPageController {

    @GetMapping("/ingest")
    public String ingestPage() {
        return "ingest";
    }
}
