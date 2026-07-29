# 클라이언트 문서 적재 & RAG 임베딩 가이드

> 프로젝트: `my-webflux-mcp-client` / `my-webflux-mcp-server`
> Spring AI 버전: `1.1.8`
> 작성일: 2026-03-05 / 최종 수정: 2026-07-28

---

## 1. 기능 개요

클라이언트 로컬에 있는 PDF / 마크다운 / 텍스트 파일을 서버의 RAG 벡터 DB(PgVector)에 임베딩하는 보조 기능입니다.

> **"적재"란?** 파일을 서버 디스크에 전송하는 것이 아닙니다.
> 파일 내용을 청킹·임베딩하여 벡터 DB에 저장하는 작업입니다. 원본 파일은 클라이언트 측에만 남습니다.

### 1-1. 주 기능과의 차이

| 구분 | 주 기능 (서버 자체 임베딩) | 보조 기능 (클라이언트 적재 임베딩) |
|------|--------------------------|--------------------------------------|
| 트리거 | `POST /api/documents/reindex` (서버 직접 호출) | `POST /api/documents/ingest` (클라이언트 경유) |
| 문서 위치 | 서버 `C:/workspace-test/upload/data` | 클라이언트 로컬 또는 브라우저 파일 선택 |
| 진행 방식 | 서버 내부 CompletableFuture | MCP Tool 호출 → 완료 대기 후 결과 반환 |
| 실시간 진행률 | 없음 | 없음 (완료 후 결과 일괄 반환) |
| 문서 요약 | 없음 | MCP Sampling → 클라이언트 Ollama 위임 |
| 고아 정리 대상 | O (디스크 스캔 기반) | X (sourceClient 있으므로 제외) |

### 1-2. 활용하는 MCP 기능

이 기능은 MCP 프로토콜의 **Progress**와 **Sampling** 두 가지 기능을 활용합니다.

| MCP 기능 | 방향 | 역할 |
|---------|------|------|
| **Progress** | 서버 → 클라이언트 | 서버가 ETL 파이프라인 각 단계 완료를 클라이언트에 전송 (클라이언트 로그로 확인) |
| **Sampling** | 서버 → 클라이언트 → 서버 | 서버가 클라이언트의 Ollama LLM에 문서 요약을 위임 |

> **참고**: 현재 클라이언트 구현은 서버의 Progress 알림을 별도 UI에 표시하지 않습니다.
> 적재는 MCP Tool 완료까지 동기 대기 후 최종 결과를 반환하는 방식으로 동작합니다.

---

## 2. 아키텍처

### 2-1. 전체 데이터 흐름

```
[브라우저 / curl]
    │
    └── POST /api/documents/ingest
         │  (multipart/form-data)
         ▼
[DocumentUploadController]
    │  base64 인코딩
    ▼
[McpAsyncClient.callTool("ingestDocument")]
    │  (MCP Tool 완료까지 reactive 대기)
    ▼
[MCP 서버 — DocumentClientUploadServiceImpl]
    │
    ├── 1/4 텍스트 추출 ──── ctx.progress(1/4) ───► [클라이언트 서버 로그]
    │
    ├── Sampling 요청 ────────────────────────────► [McpClientEventHandler.@McpSampling]
    │   ◄── Ollama 요약 응답 ──────────────────────┤         │ ChatModel.call()
    │                                              │         ▼
    ├── 2/4 요약 완료 ──── ctx.progress(2/4) ──────► [클라이언트 서버 로그]    [Ollama LLM]
    │
    ├── 3/4 청킹 완료 ──── ctx.progress(3/4) ──────► [클라이언트 서버 로그]
    │
    └── 4/4 임베딩 완료 ── ctx.progress(4/4) ──────► [클라이언트 서버 로그]
         │
         └── MCP Tool 결과 반환 (완료 메시지 문자열)
              │
    [DocumentUploadController]
         │  200 OK + {success: true, filename, message}
         ▼
[브라우저] 완료 메시지 표시
```

### 2-2. 핵심 클래스 역할

| 클래스 | 위치 | 역할 |
|--------|------|------|
| `DocumentClientUploadServiceImpl` | 서버 | `@McpTool ingestDocument` — ETL + Progress 전송 + Sampling 요청 |
| `DocumentUploadController` | 클라이언트 | REST 엔드포인트, `McpAsyncClient.callTool()` 호출, 완료 결과 반환 |
| `McpClientEventHandler` | 클라이언트 | `@McpSampling` → Ollama 위임, `@McpLogging` → 서버 로그 출력 |
| `ingest.html` | 클라이언트 | 드래그&드롭 적재 UI, 완료/실패 상태 표시 |

---

## 3. 서버 구현 상세 (`DocumentClientUploadServiceImpl`)

### 3-1. MCP Tool 시그니처

```java
@McpTool(name = "ingestDocument", description = "...")
public Mono<String> ingestDocument(
        McpAsyncRequestContext ctx,          // MCP 컨텍스트 (Progress/Sampling 전송)
        @McpToolParam(...) String jobId,     // 작업 추적용 ID (클라이언트 자동 생성)
        @McpToolParam(...) String filename,  // 파일명 (예: guide.md, report.pdf)
        @McpToolParam(...) String content,   // 파일 내용 (텍스트: UTF-8, PDF: Base64)
        @McpToolParam(...) String mimeType   // application/pdf, text/markdown, text/plain
)
```

> `McpAsyncRequestContext`는 `@McpTool` 메서드의 **첫 번째 파라미터**에 위치해야 합니다.
> `@McpToolParam` 어노테이션은 컨텍스트 파라미터에는 붙이지 않습니다.

### 3-2. ETL 파이프라인 단계

```
[1/4] 텍스트 추출
   ├── PDF  → base64 디코딩 → PagePdfDocumentReader(ByteArrayResource) → 페이지별 Document
   ├── MD   → UTF-8 디코딩 → 단일 Document
   └── TXT  → UTF-8 디코딩 → 단일 Document
        ↓  ctx.progress(1/4, "텍스트 추출 완료")

[2/4] MCP Sampling — 클라이언트 Ollama에 문서 요약 위임
   ├── ctx.sampleEnabled() 확인 → false면 건너뜀
   ├── ctx.sample(spec → spec.message(요약 프롬프트).maxTokens(300))
   └── Mono<CreateMessageResult> 응답 → 요약 텍스트 추출
        ↓  ctx.progress(2/4, "요약 완료")

[3/4] ContentFormatTransformer + DocumentChunkTransformer
   ├── HTML 태그 제거, 공백 정규화, 특수문자 정리 (normalization.enabled: true 시)
   └── TokenTextSplitter (chunkSize=4000) → 청크 분할
        ↓  ctx.progress(3/4, "청킹 완료 (N개 청크)")

[4/4] 벡터 저장소 임베딩 + 메타데이터 저장
   ├── pgVectorStore.add(chunks)  → ONNX 768차원 임베딩
   └── DocumentMetadata 저장 (filename, chunkIndex, contentHash, indexedAt, sourceClient)
        ↓  ctx.progress(4/4, "임베딩 완료 (N개 청크 저장)")
        └── Mono<String> 완료 메시지 반환
```

### 3-3. Sampling API 사용 패턴

```java
// McpAsyncRequestContext.sample() — Consumer<SamplingSpec> 방식
return ctx.sampleEnabled()
    .flatMap(enabled -> {
        if (!enabled) return Mono.just("(Sampling 미지원)");

        return ctx.sample(spec -> spec
                .message("요약 프롬프트 텍스트")
                .maxTokens(300)
        )
        .map(result -> {
            if (result.content() instanceof McpSchema.TextContent tc) {
                return tc.text();
            }
            return "(비텍스트 응답)";
        })
        .onErrorResume(e -> Mono.just("(요약 실패)"));
    });
```

### 3-4. Progress API 사용 패턴

```java
// McpAsyncRequestContext.progress() — Consumer<ProgressSpec> 방식
ctx.progress(p -> p
        .progress(1)            // 현재 단계 (double)
        .total(4)               // 전체 단계 수 (double)
        .message("단계 설명")   // 선택적 메시지 (meta 필드)
).thenReturn(nextValue)         // Mono<Void> → 다음 체인으로
```

> `progressToken`은 MCP 자동 구성이 Tool 호출 요청에서 추출합니다.
> 개발자가 직접 지정할 필요 없습니다.

### 3-5. PDF 추출 — ByteArrayResource 패턴

파일이 물리적 경로 없이 메모리(byte[])에 있을 때 `PagePdfDocumentReader`를 사용하는 방법:

```java
ByteArrayResource resource = new ByteArrayResource(pdfBytes) {
    @Override
    public String getFilename() {
        return filename;  // 파일명 명시 (메타데이터에 사용됨)
    }
};

PagePdfDocumentReader reader = new PagePdfDocumentReader(
        resource,
        PdfDocumentReaderConfig.builder()
                .withPageTopMargin(0)
                .withPagesPerDocument(1)
                .build()
);
List<Document> pages = reader.read();
```

---

## 4. 클라이언트 구현 상세

### 4-1. `DocumentUploadController` — MCP Tool 동기 호출

클라이언트는 MCP Tool 호출 완료까지 reactive 방식으로 대기한 후 결과를 반환합니다.

```
POST /api/documents/ingest (multipart)
    → DataBufferUtils.join() → 파일 바이트 수집
    → Base64 인코딩 (PDF) 또는 UTF-8 변환 (MD/TXT)
    → McpAsyncClient.callTool("ingestDocument", args)
    → (MCP Tool 처리 중 — 서버에서 Progress/Sampling 처리)
    → Mono<CallToolResult> 응답
    → 200 OK + {success: true, filename, message}
```

`jobId`는 컨트롤러가 자동 생성합니다 (`"ingest-" + System.currentTimeMillis()`).

### 4-2. `DocumentUploadController` — McpAsyncClient 주입

**MCP Tool 직접 호출 패턴:**

```java
Map<String, Object> args = new HashMap<>();
args.put("jobId", "ingest-" + System.currentTimeMillis());
args.put("filename", filename);
args.put("content", content);
args.put("mimeType", mimeType);

client.callTool(new McpSchema.CallToolRequest("ingestDocument", args))
    .map(result -> ((McpSchema.TextContent) result.content().get(0)).text())
    ...
```

### 4-3. `McpClientEventHandler` — Sampling 처리

서버의 MCP Sampling 요청(문서 요약)을 클라이언트 Ollama에 위임합니다.

```java
@McpSampling(clients = "mcp-server")
public Mono<McpSchema.CreateMessageResult> handleSampling(McpSchema.CreateMessageRequest request) {
    // request.messages() → Spring AI Prompt 변환
    // chatModel.call(prompt) → Ollama 응답
    // → CreateMessageResult 반환
}
```

Progress 알림(`ctx.progress()`)은 서버에서 클라이언트로 전송되지만, 현재 구현에서는
`@McpLogging` 핸들러를 통해 서버 로그로만 출력됩니다 (별도 UI 연동 없음).

---

## 5. REST API 명세

### 5-1. `POST /api/documents/ingest` — 파일 적재

| 항목 | 값 |
|------|-----|
| Method | POST |
| Content-Type | `multipart/form-data` |
| 파라미터 | `file` (FilePart) |
| 최대 파일 크기 | 50MB |
| 지원 형식 | `.pdf`, `.md`, `.txt` |
| 응답 방식 | 완료까지 대기 후 200 OK 반환 |

**성공 응답 (200 OK):**

```json
{
  "success": true,
  "filename": "document.pdf",
  "message": "[ingest-1710000000000] document.pdf 임베딩 완료 — 5개 청크가 RAG 지식 베이스에 추가되었습니다."
}
```

**오류 응답 (200 OK, success: false):**

```json
{
  "success": false,
  "message": "PDF, 마크다운(.md), 텍스트(.txt) 파일만 지원합니다."
}
```

### 5-2. `POST /api/documents/index-local` — 로컬 파일 인덱싱

이미 `C:/workspace-test/upload/client_data`에 파일이 있는 경우 사용합니다.

| 항목 | 값 |
|------|-----|
| Method | POST |
| 파라미터 | `filename` (query param) |
| 응답 방식 | 완료까지 대기 후 200 OK 반환 |

**요청 예시:**

```
POST /api/documents/index-local?filename=guide.md
```

---

## 6. 적재 UI (`/ingest`)

`GET http://localhost:8080/ingest` 에서 접근합니다.

### UI 구성

```
┌─────────────────────────────────────────────┐
│ RAG 지식 베이스 적재        ← 채팅으로 돌아가기 │
├─────────────────────────────────────────────┤
│ 문서 선택 및 적재                             │
│ ┌─────────────────────────────────────────┐ │
│ │  📄                                     │ │
│ │  여기에 파일을 끌어다 놓거나 클릭하세요  │ │
│ │  .pdf / .md / .txt 지원                 │ │
│ │  selected: guide.md (12.3 KB)           │ │
│ └─────────────────────────────────────────┘ │
│ [임베딩 시작]                                │
│                                             │
│ ℹ 임베딩 중입니다. 파일 크기에 따라 시간이   │
│   걸릴 수 있습니다...                        │
│   (완료 후 결과 표시)                        │
│                                             │
│ ✓ [파일명] 임베딩 완료 — N개 청크...         │
└─────────────────────────────────────────────┘
```

### 브라우저 동작 순서

1. 파일 선택 (드래그&드롭 또는 클릭)
2. `[임베딩 시작]` 클릭
3. `POST /api/documents/ingest` 요청 전송 (동기 대기)
4. 서버 측에서 ETL 처리 (Progress 전송은 서버 로그에서 확인)
5. 완료 후 성공/실패 메시지 표시

---

## 7. 테스트 방법

### 사전 준비

```
1. PostgreSQL (mcpdb — 서버용 / chatdb — 클라이언트용) 기동
2. Ollama 기동 및 모델 로드
3. my-webflux-mcp-server 기동  (포트 9090)
4. my-webflux-mcp-client 기동  (포트 8080)
5. 채팅 화면 사이드바에서 mcp-server 연결 확인
```

테스트 파일 준비:
```
C:/workspace-test/upload/client_data/sample.md   (테스트용 마크다운)
C:/workspace-test/upload/client_data/sample.pdf  (테스트용 PDF, 선택)
```

---

### 7-1. 브라우저 UI 테스트 (권장)

```
1. http://localhost:8080/ingest 접속
2. sample.md 또는 sample.pdf 파일 선택 (드래그&드롭 또는 클릭)
3. [임베딩 시작] 버튼 클릭
4. 로딩 메시지 표시 → 완료 후 결과 메시지 확인
5. 완료 후 http://localhost:8080/ (채팅)으로 이동
6. 적재한 문서의 내용 관련 질문 → RAG 검색 결과 확인
```

---

### 7-2. curl — 파일 적재

```bash
curl -s -X POST http://localhost:8080/api/documents/ingest \
  -F "file=@C:/workspace-test/upload/client_data/sample.md"
```

**예상 출력:**

```json
{
  "success": true,
  "filename": "sample.md",
  "message": "[ingest-1710000000000] sample.md 임베딩 완료 — 3개 청크가 RAG 지식 베이스에 추가되었습니다."
}
```

---

### 7-3. curl — 로컬 파일 인덱싱

`client_data` 디렉터리에 이미 파일이 있는 경우:

```bash
curl -s -X POST \
  "http://localhost:8080/api/documents/index-local?filename=sample.md"
```

---

### 7-4. curl — PDF 적재 테스트

```bash
curl -s -X POST http://localhost:8080/api/documents/ingest \
  -F "file=@C:/workspace-test/upload/client_data/report.pdf"
```

---

### 7-5. HTTP 파일 — IntelliJ / VS Code REST Client

```http
### 1. 파일 적재 (완료 대기)
POST http://localhost:8080/api/documents/ingest
Content-Type: multipart/form-data; boundary=----Boundary

------Boundary
Content-Disposition: form-data; name="file"; filename="sample.md"
Content-Type: text/markdown

< C:/workspace-test/upload/client_data/sample.md
------Boundary--

### 2. 로컬 인덱싱
POST http://localhost:8080/api/documents/index-local?filename=sample.md
```

---

### 7-6. 임베딩 결과 확인 — MCP Tool 호출

채팅 페이지(`http://localhost:8080/`)에서 다음과 같이 질문합니다:

```
"방금 적재한 [문서명] 파일의 내용을 요약해 줘"
"[적재한 문서의 핵심 키워드]에 대해 설명해 줘"
```

또는 Swagger UI(`http://localhost:8080/swagger-ui.html`)에서 직접 MCP Tool 확인:

```
describeKnowledgeBase  → 인덱싱된 파일 목록에 적재 파일 포함 여부 확인
searchDocuments(query) → 적재 문서 관련 내용 검색 결과 확인
```

---

### 7-7. 서버 로그 확인 포인트

정상 처리 시 서버 로그에 다음이 출력됩니다:

```
[적재] 요청 수신 — jobId: ingest-1710000000000, filename: sample.md, mimeType: text/markdown
[텍스트 추출][markdown] sample.md — 1234자
[적재][ingest-1710000000000] 텍스트 추출 완료 — 1페이지
[Sampling] 클라이언트 Sampling 미지원 — 요약 생략   ← Ollama Sampling 미지원 시
[적재][ingest-1710000000000] 요약 완료 — 85자        ← Sampling 성공 시
[적재][ingest-1710000000000] 청킹 완료 — 3개 청크
[적재][ingest-1710000000000] 임베딩 완료 — 3개 청크 저장
[메타데이터 저장] sample.md — 3개 청크, 요약: 85자
```

클라이언트 로그:

```
[적재] 파일 수신 — filename: sample.md
[MCP] 샘플링 요청 수신 — 메시지 수: 1, maxTokens: 300   ← Sampling 발생 시
[MCP] 샘플링 응답 생성 완료 — 길이: 85자
[적재] MCP Tool 완료
```

---

## 8. 오류 상황별 대처

| 증상 | 원인 | 해결 |
|------|------|------|
| `MCP 서버가 연결되지 않았습니다` | 서버 미기동 또는 `application.yml` URL 오류 | 서버 기동 확인, 채팅 화면에서 mcp-server 연결 |
| 적재 요청이 타임아웃됨 | 대용량 파일 처리 시간 초과 | `request-timeout` 값 증가 (예: `120s`) |
| `(Sampling 미지원)` 요약 | 클라이언트 Sampling 기능 비활성화 | 정상 동작. Sampling은 선택적 기능으로, 미지원 시 요약 없이 계속 진행 |
| PDF 추출 실패 | 비표준 PDF 또는 암호화된 PDF | 표준 PDF 사용. 암호화 해제 후 적재 |
| `파일 크기가 50MB를 초과합니다` | 대용량 파일 | 파일 분할 후 적재 |
| 청크 수 0개 | 내용이 너무 짧거나 비어있음 | `min-chunk-length-to-embed` (기본값 50자) 이상의 내용 필요 |
| `인증 실패 — 적재가 거부되었습니다` | MCP_API_KEY 미설정 또는 불일치 | 서버·클라이언트 `app.mcp-api-key` 동일하게 설정 |
| reindex 후 적재 문서 사라짐 | (구버전 이슈) `sourceClient IS NULL` 조건 미적용 | 현행 버전에서 수정됨 — MCP 적재 문서는 고아 정리 대상 제외 |

---

## 9. 주요 설정 파라미터

### 서버 (`my-webflux-mcp-server/src/main/resources/application.yml`)

```yaml
app:
  document:
    chunk-size: 4000             # TokenTextSplitter 청크 크기 (토큰)
    min-chunk-size-chars: 350    # 최소 청크 크기 (문자)
    min-chunk-length-to-embed: 50 # 임베딩 최소 길이
    normalization:
      enabled: true              # false 시 정규화 건너뜀
  security:
    api-keys:
      - "${MCP_API_KEY:}"        # 미설정 시 빈 문자열 → 모든 적재 차단
```

### 클라이언트 (`application.yml`)

```yaml
spring:
  ai:
    mcp:
      client:
        request-timeout: 60s    # MCP 요청 타임아웃 — 대용량 파일 시 증가 필요
app:
  mcp-api-key: "${MCP_API_KEY:}" # 서버의 api-keys 목록과 동일한 값으로 설정
```

---

## 10. 관련 파일 목록

### 서버 (my-webflux-mcp-server)

| 파일 | 설명 |
|------|------|
| `service/impl/DocumentClientUploadServiceImpl.java` | `@McpTool ingestDocument` — ETL 파이프라인 + Progress 전송 + Sampling 요청 |
| `repository/DocumentMetadataRepository.java` | `findAllDistinctFilenames()` — `sourceClient IS NULL` 조건으로 MCP 적재 문서 고아 정리 제외 |

### 클라이언트 (my-webflux-mcp-client)

| 파일 | 설명 |
|------|------|
| `controller/DocumentUploadController.java` | 적재 REST API (`/ingest`, `/index-local`) — MCP Tool 완료 대기 후 결과 반환 |
| `controller/UploadPageController.java` | `GET /ingest` → ingest.html |
| `resources/templates/ingest.html` | 드래그&드롭 적재 UI, 완료 결과 표시 |
| `handler/McpClientEventHandler.java` | `@McpSampling` → Ollama 위임, `@McpLogging` → 서버 로그 출력 |
