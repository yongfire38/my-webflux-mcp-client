package com.example.client.controller;

import java.util.List;

import com.example.client.config.McpServerRegistry;
import com.example.client.dto.ServerStatusDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@RestController
@RequestMapping("/api/mcp/servers")
@RequiredArgsConstructor
public class McpServerController {

    private final McpServerRegistry mcpServerRegistry;

    /** 전체 서버 상태 목록 조회. */
    @GetMapping
    public Mono<List<ServerStatusDto>> getServers() {
        return Mono.fromCallable(mcpServerRegistry::getServerStatuses)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 특정 서버 상태 단건 조회. */
    @GetMapping("/{name}")
    public Mono<ServerStatusDto> getServer(@PathVariable String name) {
        return Mono.fromCallable(() -> mcpServerRegistry.getServerStatus(name))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 서버 연결 (재연결 포함). */
    @PostMapping("/{name}/connect")
    public Mono<ServerStatusDto> connect(@PathVariable String name) {
        log.info("[API] MCP 서버 연결 요청: {}", name);
        return Mono.fromCallable(() -> mcpServerRegistry.connect(name))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(IllegalArgumentException.class,
                        e -> Mono.just(new ServerStatusDto(name, "unknown", "FAILED",
                                e.getMessage(), List.of(), false)));  // restrictedAllowed=false
    }

    /** 서버 연결 해제. */
    @PostMapping("/{name}/disconnect")
    public Mono<Void> disconnect(@PathVariable String name) {
        log.info("[API] MCP 서버 연결 해제 요청: {}", name);
        return Mono.fromRunnable(() -> mcpServerRegistry.disconnect(name))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /** 위험 작업 허용 토글. */
    @PostMapping("/{name}/restricted/{allowed}")
    public Mono<ServerStatusDto> setRestrictedAllowed(@PathVariable String name,
                                                       @PathVariable boolean allowed) {
        log.info("[API] MCP 서버 위험 작업 권한 변경: {} → {}", name, allowed);
        return Mono.fromCallable(() -> mcpServerRegistry.setRestrictedAllowed(name, allowed))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
