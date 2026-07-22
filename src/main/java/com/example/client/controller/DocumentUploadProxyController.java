package com.example.client.controller;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentUploadProxyController {

    private final WebClient serverWebClient;

    /**
     * 마크다운 파일을 서버에 중계 업로드합니다.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<String>> uploadDocuments(
            @RequestPart("files") Flux<FilePart> files,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {

        return files.collectList().flatMap(fileList -> {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            fileList.forEach(filePart -> {
                Flux<DataBuffer> content = filePart.content();
                builder.asyncPart("files", content, DataBuffer.class)
                        .filename(filePart.filename())
                        .contentType(MediaType.TEXT_PLAIN);
            });

            return serverWebClient.post()
                    .uri("/api/documents/upload")
                    .header(HttpHeaders.AUTHORIZATION, authHeader)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .toEntity(String.class)
                    .onErrorResume(WebClientResponseException.class, e ->
                            Mono.just(ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString())));
        });
    }

    /**
     * 서버 파일시스템 재인덱싱 요청을 중계합니다.
     */
    @PostMapping("/reindex")
    public Mono<ResponseEntity<String>> reindex(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        return serverWebClient.post()
                .uri("/api/documents/reindex")
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .toEntity(String.class)
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString())));
    }

    /**
     * ETL 진행 상태를 중계합니다.
     */
    @GetMapping("/status")
    public Mono<ResponseEntity<String>> status(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        return serverWebClient.get()
                .uri("/api/documents/status")
                .header(HttpHeaders.AUTHORIZATION, authHeader)
                .retrieve()
                .toEntity(String.class)
                .onErrorResume(WebClientResponseException.class, e ->
                        Mono.just(ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString())));
    }
}
