package com.example.client.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties("app.mcp.stdio")
public class AppStdioMcpProperties {

    private Map<String, StdioServerDef> servers = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class StdioServerDef {
        private String command;
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new LinkedHashMap<>();
    }
}
