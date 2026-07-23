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
| 문서 업로드 UI | http://localhost:8080/upload |

---

## 사전 준비

### 1. MCP 서버 기동 (권장)

서버 없이도 클라이언트 단독 기동이 가능합니다. 단, RAG 채팅·문서 업로드는 서버 연결 시에만 동작합니다.

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
| `MCP_API_KEY` | 서버 `uploadAndIndexDocument` 호출 인증 키. 서버의 `app.security.api-keys`와 동일한 값으로 설정. | 빈 문자열 (업로드 거부) |

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

- **RAG 탭**: `searchDocuments` 도구를 강제 선호출 → 검색 결과를 system 메시지로 주입 → Ollama 스트리밍 답변. 문서 기반 정확 답변에 적합.
- **일반 채팅 탭**: 전체 MCP 도구 목록을 LLM에 제공하고 호출 여부는 LLM이 자율 판단. 자유 대화에 적합.
- **세션 관리**: 사이드바에서 세션 목록 확인·선택·삭제. 첫 메시지 30자로 제목 자동 생성.
- **채팅 이력 복원**: 세션 선택 시 JDBC ChatMemory에서 이전 대화 이력을 불러옵니다.
- **Ollama 모델 선택**: 드롭다운으로 실시간 모델 전환.
- **마크다운 렌더링 + XSS 방어**: `marked.js`로 렌더링, `DOMPurify`로 새니타이징.

### 문서 업로드 UI (`/upload` → upload.html)

- 파일 선택 또는 드래그앤드롭 업로드 (PDF / 마크다운, 50MB 제한)
- 파일을 base64로 인코딩 후 MCP `uploadAndIndexDocument` 도구 호출
- 서버에서 Progress 4단계(추출 → 요약 → 청킹 → 임베딩)를 전송합니다

### MCP 선택적 연결 (`McpClientHolder`)

서버 없이도 클라이언트가 기동됩니다.

| 상황 | 동작 |
|------|------|
| 기동 시 서버 연결 성공 | 즉시 MCP 도구 사용 가능 |
| 기동 시 서버 없음 | 일반 채팅만 가능. RAG 요청 시 자동 재연결 시도 |
| 재연결 성공 | RAG·업로드 기능 복구 |
| 재연결 실패 | SSE로 오류 안내. 5초 쿨다운 후 재시도 허용 |

> `McpClientAutoConfiguration`을 제외하고 `McpOptionalConfig` + `McpClientHolder`로 대체 구성합니다.
> 재연결 시 `WebClientStreamableHttpTransport`를 매번 신규 생성합니다(실패 후 인스턴스 재사용 불가).

### MCP 이벤트 핸들러 (`McpClientEventHandler`)

| 어노테이션 | 동작 |
|-----------|------|
| `@McpLogging` | 서버 MCP 로그 알림을 SLF4J 레벨별로 라우팅 |
| `@McpSampling` | 서버의 LLM 추론 위임 요청을 Ollama로 처리 후 반환. `uploadAndIndexDocument` 문서 요약에 사용. |
| `@McpToolListChanged` | 서버 도구 목록 변경 시 로그 기록 |
| `@McpResourceListChanged` | 서버 리소스 변경(문서 인덱싱 완료 등) 시 로그 기록 |
| `@McpPromptListChanged` | 서버 프롬프트 변경 시 로그 기록 |

---

## REST API

### 채팅

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/chat/rag/stream` | RAG 채팅 SSE 스트리밍. 파라미터: `message`, `sessionId`, `model` |
| `GET` | `/api/chat/simple/stream` | 일반 채팅 SSE 스트리밍. 파라미터: `message`, `sessionId`, `model` |

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
| `POST` | `/api/documents/upload` | 파일 업로드 → MCP `uploadAndIndexDocument` 호출 (50MB, `files` 파트명) |
| `GET` | `/api/ollama/models` | Ollama 설치 모델 목록 조회 |

---

## 프로젝트 구조

```
src/main/java/com/example/client/
├── ClientApplication.java
├── config/
│   ├── ChatClientConfig.java          # ChatClient, JDBC ChatMemory, MessageChatMemoryAdvisor
│   ├── EgovCommonConfig.java
│   ├── McpClientHolder.java           # MCP 클라이언트 래퍼: 선택적 연결·동적 재연결
│   ├── McpOptionalConfig.java         # McpClientHolder 빈 생성 + 기동 시 초기 연결 시도
│   ├── OllamaConfig.java              # Ollama ChatModel 설정
│   └── SwaggerConfig.java
├── controller/
│   ├── ChatController.java            # /api/chat/rag/stream, /simple/stream (SSE)
│   ├── ChatPageController.java        # / → chat.html
│   ├── ChatSessionController.java     # /api/sessions CRUD
│   ├── DocumentUploadController.java  # /api/documents/upload → MCP 도구 호출
│   ├── OllamaModelController.java     # /api/ollama/models
│   └── UploadPageController.java      # /upload → upload.html
├── dto/
│   ├── ChatMessageDto.java
│   └── ChatSessionDto.java
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
        ├── ChatServiceImpl.java        # RAG(강제 선호출) / Simple(LLM 자율) 구현
        ├── ChatSessionServiceImpl.java
        └── OllamaModelServiceImpl.java

src/main/resources/
├── application.yml
├── static/js/
│   ├── marked.min.js                  # 마크다운 렌더링
│   └── purify.min.js                  # XSS 방어 (DOMPurify)
└── templates/
    ├── chat.html                      # 채팅 UI (RAG·일반 탭, 세션 사이드바, SSE 스트리밍)
    └── upload.html                    # 문서 업로드 UI
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
  mcp-client-id: webflux-mcp-client    # 서버가 문서 출처 식별에 사용. 다중 클라이언트 배포 시 고유값으로 변경
  mcp-api-key: "${MCP_API_KEY:}"       # 서버 업로드 도구 인증 키
  rag:
    query-transform:
      translation-enabled: false        # 질의 한국어 번역 (기본 off)
      rewrite-enabled: false            # 질의 재작성 (기본 off)
```

---

## 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| 기동 시 "MCP 초기 연결 실패" 로그 | MCP 서버 미기동 | 정상 동작. 일반 채팅은 바로 사용 가능. RAG 요청 시 자동 재연결 시도. |
| RAG 채팅 "MCP 서버 연결 없음" | MCP 서버 미기동 또는 연결 끊김 | 서버 기동 후 RAG 탭에서 메시지 전송 시 자동 재연결 |
| 업로드 인증 실패 | `MCP_API_KEY` 미설정 또는 서버·클라이언트 값 불일치 | 양쪽 환경변수 동일한 값으로 설정 후 재기동 |
| chatdb 연결 오류 | PostgreSQL 미기동 또는 포트 오류 | 5433 포트 Docker 컨테이너 확인 |
| RAG 결과 "관련 문서 없음" | 벡터 DB에 인덱싱된 문서 없음 | `/upload`에서 파일 업로드 또는 서버 재인덱싱 |
| 업로드 타임아웃 | 대용량 파일 처리 지연 | `app.yml`의 `request-timeout` 증가 (예: 120000) |
| Ollama 모델 목록 비어 있음 | Ollama 미기동 | `ollama serve` 실행 후 재시도 |
| 세션 선택 후 이력 미표시 | chatdb 연결 문제 | DB 상태 및 `spring_ai_chat_memory` 테이블 확인 |
