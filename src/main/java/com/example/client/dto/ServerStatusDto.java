package com.example.client.dto;

import java.util.List;

public record ServerStatusDto(
        String name,
        String type,
        String status,
        String lastError,
        List<String> tools,
        boolean restrictedAllowed
) {}
