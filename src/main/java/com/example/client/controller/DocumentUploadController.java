package com.example.client.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 클라이언트 로컬 문서를 서버 RAG DB에 임베딩하는 업로드 컨트롤러.
 *
 * 엔드포인트:
 *   POST /api/documents/upload           파일 업로드 → MCP Tool 호출 → 완료 결과 반환
 *
 * 지원 형식: PDF (.pdf), 마크다운 (.md)
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
public class DocumentUploadController {

    private static final String CLIENT_DATA_DIR = "C:/workspace-test/upload/client_data";
    private static final long MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50MB

    private final McpAsyncClient mcpAsyncClient;

    /**
     * Spring AI 자동 구성은 설정된 연결 수만큼의 McpAsyncClient를 List로 등록합니다.
     * 이 프로젝트는 'mcp-server' 단일 연결이므로 첫 번째 클라이언트를 사용합니다.
     */
    public DocumentUploadController(List<McpAsyncClient> mcpAsyncClients) {
        if (mcpAsyncClients == null || mcpAsyncClients.isEmpty()) {
            throw new IllegalStateException("MCP Async Client가 등록되지 않았습니다. application.yml 설정을 확인하세요.");
        }
        this.mcpAsyncClient = mcpAsyncClients.get(0);
    }

    /**
     * 파일 업로드 엔드포인트.
     *
     * 파일을 base64로 인코딩하여 MCP Tool(uploadAndIndexDocument)을 호출하고,
     * 임베딩이 완료되면 결과를 반환합니다.
     */
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

        String mimeType = safeFilename.toLowerCase().endsWith(".pdf")
                ? "application/pdf"
                : "text/markdown";

        log.info("[업로드] 파일 수신 — filename: {}", safeFilename);

        return DataBufferUtils.join(filePart.content())
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    if (bytes.length > MAX_FILE_SIZE_BYTES) {
                        return Mono.just(errorResponse("파일 크기가 50MB를 초과합니다."));
                    }

                    String base64 = Base64.getEncoder().encodeToString(bytes);
                    return callMcpUploadTool(safeFilename, base64, mimeType)
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

    /**
     * 서버의 로컬 client_data 디렉터리에 있는 파일을 임베딩합니다.
     * (파일이 이미 C:/workspace-test/upload/client_data 에 있는 경우)
     */
    @PostMapping("/index-local")
    public Mono<Map<String, Object>> indexLocalFile(
            @org.springframework.web.bind.annotation.RequestParam String filename) {

        String safeFilename = Paths.get(filename).getFileName().toString();
        if (!isSupportedFile(safeFilename)) {
            return Mono.just(errorResponse("PDF 또는 마크다운(.md) 파일만 지원합니다."));
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
                    return callMcpUploadTool(safeFilename, base64, mimeType);
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

    private Mono<String> callMcpUploadTool(String filename, String base64Content, String mimeType) {
        Map<String, Object> args = new HashMap<>();
        args.put("jobId", "upload-" + System.currentTimeMillis());
        args.put("filename", filename);
        args.put("base64Content", base64Content);
        args.put("mimeType", mimeType);

        return mcpAsyncClient.callTool(
                        new McpSchema.CallToolRequest("uploadAndIndexDocument", args))
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
