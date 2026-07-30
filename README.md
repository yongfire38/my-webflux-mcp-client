# my-webflux-mcp-client

Spring AI MCP 클라이언트 기반의 RAG 채팅 애플리케이션입니다.
Ollama 로컬 LLM과 my-webflux-mcp-server(:9090)를 연결해 문서 기반 질의응답과 일반 대화를 제공합니다.

---

## 환경

| 항목 | 값 |
|------|-----|
| Java | 17 |
| eGovFrame Boot | 5.0.0 (Spring Boot 3.5.6) |
| Spring AI | 1.1.8 |
| 서버 포트 | 8080 |
| MCP 서버 | http://localhost:9090 (ASYNC / Streamable HTTP) |
| 채팅 DB | PostgreSQL (localhost:5433, DB: chatdb) |
| Ollama | http://localhost:11434, 기본 모델: qwen3-4b:Q4_K_M |
| 채팅 UI | http://localhost:8080 |
| 문서 적재 UI | http://localhost:8080/ingest |

---

## 사전 준비

### 1. MCP 서버 기동 (권장)

서버 없이도 클라이언트 단독 기동이 가능합니다. 단, RAG 채팅·문서 적재는 서버 연결 시에만 동작합니다.

```bash
cd C:/workspace-team/my-webflux-mcp-server
mvn spring-boot:run
```

### 2. PostgreSQL (chatdb)

세션 정보와 채팅 메모리를 저장하는 전용 DB입니다 (서버의 ragdb:5432와 별개).

```bash
docker run -d \
  --name chatdb \
  -e POSTGRES_DB=chatdb \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5433:5432 \
  postgres:16
```

`initialize-schema: always` 설정으로 첫 실행 시 테이블이 자동 생성됩니다.

### 3. Ollama

Tool Calling을 지원하는 모델이 필요합니다.

```bash
ollama pull qwen3-4b:Q4_K_M
```

### 4. 환경변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `MCP_API_KEY` | 서버 `ingestDocument` 호출 인증 키. 서버의 `app.security.api-keys`와 동일한 값으로 설정. | 빈 문자열 (적재 거부) |

---

## 빌드 및 실행

```bash
cd C:/workspace-team/my-webflux-mcp-client
mvn clean compile
mvn spring-boot:run
```

---

## 주요 기능

### 채팅 UI (`/` → chat.html)

- **단일 탭**: 전체 MCP 도구 목록을 LLM에 제공하고 호출 여부는 LLM이 자율 판단. RAG가 필요한 질문에서 LLM이 `searchDocuments`를 자율 호출.
- **MCP 미연결 경고**: 서버가 등록돼 있으나 연결된 도구가 없으면 `"*MCP 서버 미연결 — RAG 없이 응답합니다*"` 경고를 답변 앞에 선행 삽입.
- *(구 RAG 강제 선호출 탭: `@Deprecated` 처리됨 — `/api/chat/rag/stream` 엔드포인트 잔존하나 UI에서 제거됨)*
- **세션 관리**: 사이드바에서 세션 목록 확인·선택·삭제. 첫 메시지 30자로 제목 자동 생성. 답변 완료 후 즉시 제목 갱신.
- **채팅 이력 복원**: 세션 선택 시 JDBC ChatMemory에서 이전 대화 이력을 불러옵니다.
- **Ollama 모델 선택**: 드롭다운으로 실시간 모델 전환.
- **마크다운 렌더링 + XSS 방어**: `marked.js`로 렌더링, `DOMPurify`로 새니타이징.
- **RAG 미연결 경고**: MCP 서버가 연결되지 않은 상태에서 채팅 시 `*MCP 서버 미연결 — RAG 없이 응답합니다*` 경고 메시지를 답변 앞에 표시하고 사이드바 상태를 즉시 갱신합니다.

### 문서 적재 UI (`/ingest` → ingest.html)

- 파일 선택 또는 드래그앤드롭 (PDF / 마크다운 / 텍스트, 50MB 제한)
- 파일을 base64로 인코딩 후 MCP `ingestDocument` 도구 호출
- 서버에서 Progress 4단계(추출 → 요약 → 청킹 → 임베딩)를 전송합니다
- 적재된 파일은 서버 디스크에 저장되지 않고 벡터 DB에만 저장됩니다

### MCP 서버 레지스트리 (`McpServerRegistry`)

서버를 이름 단위로 식별하고 per-server 상태를 독립적으로 관리합니다.

#### 서버 상태

| 상태 | 의미 |
|------|------|
| `DISCONNECTED` | 초기 상태. 연결 전. |
| `CONNECTED` | `initialize()` + `listTools()` ping 성공. |
| `FAILED` | 연결 중 예외 발생 또는 ping 실패(서버 사망 감지). |

#### 사이드바 동작

| 상황 | 동작 |
|------|------|
| 기동 시 서버 연결 성공 | CONNECTED(녹색). 즉시 MCP 도구 사용 가능. |
| 기동 시 서버 없음 | DISCONNECTED. 일반 채팅만 가능. 사이드바 [연결] 버튼으로 수동 연결. |
| 채팅 요청 시 서버 사망 감지 | FAILED(빨간색). RAG 경고 메시지 표시 후 사이드바 상태 자동 갱신. |
| [연결] 버튼 클릭 | 재연결 시도. 성공 시 CONNECTED로 전환. |

> 재연결 시 `WebClientStreamableHttpTransport`를 매번 신규 생성합니다 (실패 후 인스턴스 재사용 불가).

#### 서버 사망 감지

`getToolCallbacks()` 호출마다 각 서버에 `listTools()` ping을 보내 생존 여부를 확인합니다.
ping 실패 시 해당 서버를 FAILED로 전환하고 RAG 경고 메시지를 채팅에 표시합니다.

#### stdio MCP 서버 지원

`application.yml`에 stdio 서버를 설정하면 클라이언트가 직접 자식 프로세스를 기동합니다.

```yaml
app:
  mcp:
    stdio:
      servers:
        fs-server:
          command: "cmd"                     # Windows: cmd /c 필수 (npx는 .cmd 스크립트)
          args: ["/c", "npx", "-y",
                 "@modelcontextprotocol/server-filesystem",
                 "C:/workspace-test/upload"]
```

> Linux/macOS: `command: "npx"`, `args: ["-y", "@modelcontextprotocol/server-filesystem", "/path"]`

#### 위험 작업 제어

각 서버의 도구 중 위험 작업으로 판별된 도구는 `restrictedAllowed = false`(기본값)인 경우 호출이 차단됩니다.

- **판별 순서**: `readOnlyHint: true` → 허용 / `destructiveHint: true` 또는 `openWorldHint: true` → 차단 / 이름 휴리스틱 (write/create/delete/execute/send/deploy/drop 등)
- **사이드바 토글**: CONNECTED 상태 서버에서 "위험 작업 허용" 체크박스로 on/off 전환
- **차단 시 응답**: `"제한 작업이 차단되었습니다. 사이드바의 [위험 작업 허용] 토글을 활성화하세요."` — LLM에게 반환되어 사용자에게 안내됩니다

### MCP 이벤트 핸들러 (`McpClientEventHandler`)

| 어노테이션 | 동작 |
|-----------|------|
| `@McpLogging` | 서버 MCP 로그 알림을 SLF4J 레벨별로 라우팅 |
| `@McpSampling` | 서버의 LLM 추론 위임 요청을 Ollama로 처리 후 반환. `ingestDocument` 문서 요약에 사용. |
| `@McpToolListChanged` | 서버 도구 목록 변경 시 로그 기록 |
| `@McpResourceListChanged` | 서버 리소스 변경(문서 인덱싱 완료 등) 시 로그 기록 |
| `@McpPromptListChanged` | 서버 프롬프트 변경 시 로그 기록 |

---

## REST API

### 채팅

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/chat/simple/stream` | 채팅 SSE 스트리밍. 파라미터: `message`, `sessionId`, `model` |
| `GET` | ~~`/api/chat/rag/stream`~~ | **@Deprecated** — UI에서 제거됨. `/simple/stream` 사용. |

### 세션 관리

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/sessions` | 새 세션 생성 |
| `GET` | `/api/sessions` | 세션 목록 조회 (최신순) |
| `DELETE` | `/api/sessions/{id}` | 세션 삭제 (채팅 이력 포함) |
| `PATCH` | `/api/sessions/{id}/title` | 세션 제목 수정 |
| `GET` | `/api/sessions/{id}/messages` | 세션 채팅 이력 조회 |

### 문서 및 모델

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/documents/ingest` | 파일 적재 → MCP `ingestDocument` 호출 (50MB, `file` 파트명) |
| `POST` | `/api/documents/index-local` | 클라이언트 로컬 파일 인덱싱 (`CLIENT_DATA_DIR` 기준) |
| `GET` | `/api/ollama/models` | Ollama 설치 모델 목록 조회 |

### MCP 서버 관리

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/mcp/servers` | 전체 서버 상태 목록 |
| `GET` | `/api/mcp/servers/{name}` | 단일 서버 상태 조회 |
| `POST` | `/api/mcp/servers/{name}/connect` | 서버 연결 |
| `POST` | `/api/mcp/servers/{name}/disconnect` | 서버 연결 해제 |
| `POST` | `/api/mcp/servers/{name}/restricted/{allowed}` | 위험 작업 허용 on/off |

---

## 프로젝트 구조

```
src/main/java/com/example/client/
├── ClientApplication.java
├── config/
│   ├── ChatClientConfig.java          # ChatClient, JDBC ChatMemory, MessageChatMemoryAdvisor
│   ├── EgovCommonConfig.java
│   ├── McpServerRegistry.java         # MCP 서버 per-server 상태 관리 (DISCONNECTED/CONNECTED/FAILED)
│   │                                  #   · listTools() ping으로 서버 사망 감지
│   │                                  #   · SSE(HTTP) + stdio 두 가지 연결 유형 지원
│   │                                  #   · ToolAnnotations 기반 위험 작업 제어 + wrapWithSafetyGuard
│   ├── McpOptionalConfig.java         # McpServerRegistry 빈 생성 + 기동 시 초기 연결 시도
│   ├── OllamaConfig.java              # Ollama ChatModel 설정
│   └── SwaggerConfig.java
├── controller/
│   ├── ChatController.java            # /api/chat/rag/stream, /simple/stream (SSE)
│   ├── ChatPageController.java        # / → chat.html
│   ├── ChatSessionController.java     # /api/sessions CRUD
│   ├── DocumentIngestController.java  # /api/documents/ingest → MCP ingestDocument 호출
│   ├── McpServerController.java       # /api/mcp/servers/** (상태 조회·연결·위험 작업 허용)
│   ├── OllamaModelController.java     # /api/ollama/models
│   └── IngestPageController.java      # /ingest → ingest.html
├── dto/
│   ├── ChatMessageDto.java
│   ├── ChatSessionDto.java
│   └── ServerStatusDto.java           # 서버 상태 DTO (name, type, status, lastError, tools, restrictedAllowed)
├── entity/
│   └── ChatSessionEntity.java         # JPA (spring_ai_chat_sessions 테이블)
├── handler/
│   └── McpClientEventHandler.java     # @McpLogging, @McpSampling 등 MCP 이벤트 처리
├── repository/
│   └── ChatSessionRepository.java
└── service/
    ├── ChatService.java
    ├── ChatSessionService.java
    ├── OllamaModelService.java
    └── impl/
        ├── ChatServiceImpl.java        # Simple(LLM 자율) 구현 — RAG 강제 선호출은 @Deprecated
        │                               #   · MCP 미연결 시 Flux.concat으로 경고 메시지 선행 삽입
        ├── ChatSessionServiceImpl.java
        └── OllamaModelServiceImpl.java

src/main/resources/
├── application.yml
├── static/js/
│   ├── marked.min.js                  # 마크다운 렌더링
│   └── purify.min.js                  # XSS 방어 (DOMPurify)
└── templates/
    ├── chat.html                      # 채팅 UI (단일 탭, 서버 상태 사이드바, SSE 스트리밍)
    └── ingest.html                    # 문서 적재 UI (구 upload.html)
```

---

## application.yml 주요 설정

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/chatdb
  ai:
    ollama:
      base-url: http://localhost:11434
      chat.options.model: qwen3-4b:Q4_K_M
    mcp:
      client:
        type: ASYNC
        streamable-http.connections.mcp-server.url: http://localhost:9090
        request-timeout: 60000          # MCP 요청 타임아웃 (ms)
    chat.memory.repository.jdbc:
      initialize-schema: always         # 최초 실행 시 테이블 자동 생성
server:
  port: 8080

app:
  mcp-client-id: webflux-mcp-client    # 서버가 적재 문서의 출처 식별에 사용. 다중 클라이언트 배포 시 고유값으로 변경
  mcp-api-key: "${MCP_API_KEY:}"       # 서버 ingestDocument 도구 인증 키
  mcp:
    stdio:
      servers: {}                       # stdio MCP 서버 설정 (선택)
  rag:
    query-transform:
      translation-enabled: false        # 질의 한국어 번역 (기본 off)
      rewrite-enabled: false            # 질의 재작성 (기본 off)
```

---

## 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| 기동 시 "MCP 초기 연결 실패" 로그 | MCP 서버 미기동 | 정상 동작. 일반 채팅은 바로 사용 가능. 사이드바 [연결] 버튼으로 수동 연결. |
| 채팅 시 "MCP 서버 미연결" 경고 메시지 | MCP 서버 FAILED 또는 DISCONNECTED | 서버 기동 후 사이드바 [연결] 버튼 클릭. 경고 발생 시 사이드바 상태가 자동으로 FAILED로 갱신됨. |
| 적재 인증 실패 | `MCP_API_KEY` 미설정 또는 서버·클라이언트 값 불일치 | 양쪽 환경변수 동일한 값으로 설정 후 재기동 |
| chatdb 연결 오류 | PostgreSQL 미기동 또는 포트 오류 | 5433 포트 Docker 컨테이너 확인 |
| RAG 결과 "관련 문서 없음" | 벡터 DB에 인덱싱된 문서 없음 | `/ingest`에서 파일 적재 또는 서버 재인덱싱 |
| 적재 타임아웃 | 대용량 파일 처리 지연 | `app.yml`의 `request-timeout` 증가 (예: 120000) |
| Ollama 모델 목록 비어 있음 | Ollama 미기동 | `ollama serve` 실행 후 재시도 |
| 세션 선택 후 이력 미표시 | chatdb 연결 문제 | DB 상태 및 `spring_ai_chat_memory` 테이블 확인 |
| stdio 서버 연결 실패 (Windows) | `npx`는 `.cmd` 스크립트 — 직접 실행 불가 | `command: "cmd"`, `args: ["/c", "npx", ...]`로 설정 변경 |
| 위험 작업 도구가 차단됨 | `restrictedAllowed = false` (기본값) | 사이드바의 해당 서버 "위험 작업 허용" 체크박스 활성화 |
| 사이드바가 여전히 CONNECTED로 표시됨 | 채팅을 보내지 않으면 ping이 발생하지 않음 | 채팅을 한 번 보내거나 사이드바 새로고침(↺) 버튼 클릭 |
