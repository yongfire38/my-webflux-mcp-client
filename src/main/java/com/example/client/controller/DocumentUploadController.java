package com.example.client.controller;

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

import com.example.client.config.McpClientHolder;
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
 * 클라이언트 로컬 문서를 서버 RAG DB에 임베딩하는 업로드 컨트롤러.
 *
 * 엔드포인트:
 *   POST /api/documents/upload           파일 업로드 → MCP Tool 호출 → 완료 결과 반환
 *   POST /api/documents/index-local      로컬 파일 인덱싱 (CLIENT_DATA_DIR 기준)
 *
 * 지원 형식: PDF (.pdf), 마크다운 (.md)
 *
 * MCP 미연결 시: 각 요청에서 mcpClientHolder.getFirstClient() 가 null을 반환하므로 오류 응답.
 * 업로드 엔드포인트는 재연결 시도를 하지 않는다 — RAG 탭에서 재연결 후 사용할 것.
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentUploadController {

    private static final String CLIENT_DATA_DIR = "C:/workspace-test/upload/client_data";

    private final McpClientHolder mcpClientHolder;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> uploadDocument(
            @RequestPart("file") FilePart filePart) {

        String filename = filePart.filename();
        if (filename == null || filename.isBlank()) {
            return Mono.just(errorResponse("파일명이 없습니다."));
        }

        String safeFilename = Paths.get(filename).getFileName().toString();
        if (!isSupportedFile(safeFilename)) {
            return Mono.just(errorResponse("PDF 또는 마크다운(.md) 파일만 지원합니다."));
        }

        McpAsyncClient client = mcpClientHolder.getFirstClient();
        if (client == null) {
            return Mono.just(errorResponse(
                    "MCP 서버가 연결되지 않았습니다. RAG 탭에서 질문을 한 번 시도하면 자동 재연결됩니다."));
        }

        String mimeType = safeFilename.toLowerCase().endsWith(".pdf")
                ? "application/pdf"
                : "text/markdown";

        log.info("[업로드] 파일 수신 — filename: {}", safeFilename);

        return DataBufferUtils.join(filePart.content(), 50 * 1024 * 1024)
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    return callMcpUploadTool(client, safeFilename, base64, mimeType)
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
                    log.error("[업로드] MCP Tool 실패 — {}", e.getMessage());
                    return Mono.just(errorResponse(e.getMessage()));
                });
    }

    @PostMapping("/index-local")
    public Mono<Map<String, Object>> indexLocalFile(
            @org.springframework.web.bind.annotation.RequestParam String filename) {

        String safeFilename = Paths.get(filename).getFileName().toString();
        if (!isSupportedFile(safeFilename)) {
            return Mono.just(errorResponse("PDF 또는 마크다운(.md) 파일만 지원합니다."));
        }

        McpAsyncClient client = mcpClientHolder.getFirstClient();
        if (client == null) {
            return Mono.just(errorResponse(
                    "MCP 서버가 연결되지 않았습니다. RAG 탭에서 질문을 한 번 시도하면 자동 재연결됩니다."));
        }

        Path filePath = Paths.get(CLIENT_DATA_DIR, safeFilename);
        String mimeType = safeFilename.toLowerCase().endsWith(".pdf")
                ? "application/pdf"
                : "text/markdown";

        log.info("[로컬 인덱싱] filename: {}", safeFilename);

        return Mono.fromCallable(() -> Files.readAllBytes(filePath))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(bytes -> {
                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    return callMcpUploadTool(client, safeFilename, base64, mimeType);
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

    private Mono<String> callMcpUploadTool(McpAsyncClient client,
                                           String filename, String base64Content, String mimeType) {
        Map<String, Object> args = new HashMap<>();
        args.put("jobId", "upload-" + System.currentTimeMillis());
        args.put("filename", filename);
        args.put("base64Content", base64Content);
        args.put("mimeType", mimeType);

        return client.callTool(new McpSchema.CallToolRequest("uploadAndIndexDocument", args))
                .retryWhen(Retry.fixedDelay(1, Duration.ofSeconds(2))
                        .filter(e -> e instanceof McpTransportSessionNotFoundException
                                || (e.getMessage() != null && e.getMessage().contains("session")))
                        .doBeforeRetry(s -> log.warn("[업로드] MCP 세션 만료 감지 — 재연결 후 재시도")))
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

    private boolean isSupportedFile(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".md");
    }

    private Map<String, Object> errorResponse(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", message);
        return response;
    }
}
