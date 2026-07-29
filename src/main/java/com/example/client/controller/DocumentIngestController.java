package com.example.client.controller;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import com.example.client.config.McpServerRegistry;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * 클라이언트 로컬 문서를 서버 RAG DB에 적재(임베딩)하는 컨트롤러.
 *
 * 엔드포인트:
 *   POST /api/documents/ingest       파일 적재 → MCP ingestDocument 호출 → 완료 결과 반환
 *   POST /api/documents/index-local  로컬 파일 인덱싱 (CLIENT_DATA_DIR 기준)
 *
 * 지원 형식: PDF (.pdf), 마크다운 (.md), 텍스트 (.txt)
 * MCP 미연결 시 오류 응답 반환. 적재 전 사이드바에서 서버를 먼저 연결할 것.
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentIngestController {

    private static final String CLIENT_DATA_DIR = "C:/workspace-test/upload/client_data";

    private final McpServerRegistry mcpServerRegistry;

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> ingestDocument(
            @RequestPart("file") FilePart filePart) {

        String filename = filePart.filename();
        if (filename == null || filename.isBlank()) {
            return Mono.just(errorResponse("파일명이 없습니다."));
        }

        String safeFilename = Paths.get(filename).getFileName().toString();
        if (!isSupportedFile(safeFilename)) {
            return Mono.just(errorResponse("PDF, 마크다운(.md), 텍스트(.txt) 파일만 지원합니다."));
        }

        McpAsyncClient client = mcpServerRegistry.getClient("mcp-server");
        if (client == null) {
            return Mono.just(errorResponse(
                    "MCP 서버가 연결되지 않았습니다. 사이드바의 MCP 서버 패널에서 [연결] 버튼을 눌러 연결하세요."));
        }

        String mimeType = getMimeType(safeFilename);

        log.info("[적재] 파일 수신 — filename: {}", safeFilename);

        return DataBufferUtils.join(filePart.content(), 50 * 1024 * 1024)
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    String content = "application/pdf".equals(mimeType)
                            ? Base64.getEncoder().encodeToString(bytes)
                            : new String(bytes, StandardCharsets.UTF_8);
                    return callMcpIngestTool(client, safeFilename, content, mimeType)
                            .map(result -> {
                                Map<String, Object> response = new HashMap<>();
                                response.put("success", true);
                                response.put("filename", safeFilename);
                                response.put("message", result);
                                return response;
                            });
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.error("[적재] MCP Tool 실패 — {}", e.getMessage());
                    return Mono.just(errorResponse(e.getMessage()));
                });
    }

    @PostMapping("/index-local")
    public Mono<Map<String, Object>> indexLocalFile(
            @org.springframework.web.bind.annotation.RequestParam String filename) {

        String safeFilename = Paths.get(filename).getFileName().toString();
        if (!isSupportedFile(safeFilename)) {
            return Mono.just(errorResponse("PDF, 마크다운(.md), 텍스트(.txt) 파일만 지원합니다."));
        }

        McpAsyncClient client = mcpServerRegistry.getClient("mcp-server");
        if (client == null) {
            return Mono.just(errorResponse(
                    "MCP 서버가 연결되지 않았습니다. 사이드바의 MCP 서버 패널에서 [연결] 버튼을 눌러 연결하세요."));
        }

        Path filePath = Paths.get(CLIENT_DATA_DIR, safeFilename);
        String mimeType = getMimeType(safeFilename);

        log.info("[로컬 인덱싱] filename: {}", safeFilename);

        return Mono.fromCallable(() -> Files.readAllBytes(filePath))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(bytes -> {
                    String content = "application/pdf".equals(mimeType)
                            ? Base64.getEncoder().encodeToString(bytes)
                            : new String(bytes, StandardCharsets.UTF_8);
                    return callMcpIngestTool(client, safeFilename, content, mimeType);
                })
                .map(result -> {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", true);
                    response.put("filename", safeFilename);
                    response.put("message", result);
                    return response;
                })
                .onErrorResume(e -> {
                    log.error("[로컬 인덱싱] 실패 — {}", e.getMessage());
                    return Mono.just(errorResponse(e.getMessage()));
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Mono<String> callMcpIngestTool(McpAsyncClient client,
                                           String filename, String content, String mimeType) {
        Map<String, Object> args = new HashMap<>();
        args.put("jobId", "ingest-" + System.currentTimeMillis());
        args.put("filename", filename);
        args.put("content", content);
        args.put("mimeType", mimeType);

        return client.callTool(new McpSchema.CallToolRequest("ingestDocument", args))
                .retryWhen(Retry.fixedDelay(1, Duration.ofSeconds(2))
                        .filter(e -> e instanceof McpTransportSessionNotFoundException
                                || (e.getMessage() != null && e.getMessage().contains("session")))
                        .doBeforeRetry(s -> log.warn("[적재] MCP 세션 만료 감지 — 재연결 후 재시도")))
                .map(result -> {
                    if (result.content() != null && !result.content().isEmpty()) {
                        Object first = result.content().get(0);
                        if (first instanceof McpSchema.TextContent tc) {
                            return tc.text() != null ? tc.text() : "(빈 응답)";
                        }
                    }
                    return "(응답 없음)";
                });
    }

    private String getMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        return "text/markdown";
    }

    private boolean isSupportedFile(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".md") || lower.endsWith(".txt");
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }
}
