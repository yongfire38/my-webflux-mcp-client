# my-webflux-mcp-client

Spring AI MCP 클라이언트 기반의 RAG 채팅 애플리케이션입니다.
Ollama 로컬 LLM과 MCP 서버(my-webflux-mcp-server)를 연결해, 채팅 UI에서 문서 기반 질의응답과 일반 대화를 모두 지원합니다.

---

## 프로젝트 개요

이 애플리케이션은 사용자가 웹 브라우저에서 직접 사용하는 채팅 인터페이스입니다.
두 가지 채팅 모드를 제공합니다.

1. **RAG 채팅**: 질문을 보내면 MCP 서버의 `searchDocuments` 도구를 **강제 선호출**해 관련 문서를 먼저 검색한 후, 그 내용을 system 메시지로 주입하여 Ollama LLM이 답변합니다.
2. **일반 채팅**: MCP 도구 목록 전체를 LLM에 제공하되, 호출 여부는 LLM이 스스로 판단합니다.

또한 문서 업로드 UI를 통해 PDF/마크다운 파일을 MCP 서버의 벡터 DB에 직접 임베딩할 수 있습니다.

---

## 환경

| 항목 | 값 |
|------|-----|
| Java | 17 |
| Spring Boot | 3.2.x |
| Spring AI | 1.1.2 |
| 서버 포트 | 8080 |
| MCP 서버 연결 | http://localhost:9090 (ASYNC / streamable-http) |
| 채팅 DB | PostgreSQL (localhost:5433, DB: chatdb) |
| Ollama | http://localhost:11434, 기본 모델: qwen3-4b:Q4_K_M |
| 채팅 UI | http://localhost:8080 |
| 문서 업로드 UI | http://localhost:8080/upload |

---

## 주요 기능

### 채팅 UI (`/` → chat.html)

- **RAG 탭**: `searchDocuments` 도구를 강제 선호출 → 검색 결과를 system 메시지로 주입 → LLM 답변 스트리밍. 문서 기반의 정확한 답변에 적합합니다.
- **일반 채팅 탭**: 전체 MCP 도구를 제공하고 LLM이 필요 시 자율 호출. 자유로운 대화에 적합합니다. (예: "지금 서울 시간은?" → LLM이 `getCurrentDateTimeWithZone` 호출)
- **세션별 대화 히스토리**: PostgreSQL JDBC ChatMemory로 대화를 영속화합니다.
- **Ollama 모델 선택**: 드롭다운으로 설치된 Ollama 모델을 실시간 전환합니다.
- **마크다운 렌더링**: LLM 응답을 HTML로 렌더링합니다.

### 문서 업로드 UI (`/upload` → upload.html)

- 파일 선택 또는 드래그앤드롭으로 업로드
- `DocumentUploadController`가 파일을 읽어 base64로 인코딩 후 MCP `uploadAndIndexDocument` 도구 호출
- 임베딩 완료 후 결과를 반환합니다 (동기 대기 방식)

### MCP 이벤트 핸들러 (McpClientEventHandler.java)

서버에서 발생하는 MCP 이벤트를 수신해 처리합니다.

| 어노테이션 | 역할 |
|-----------|------|
| `@McpLogging` | 서버 MCP 로그를 수신해 클라이언트 SLF4J 로거로 출력합니다. |
| `@McpToolListChanged` | 서버 도구 목록 변경 시 감지합니다. |
| `@McpResourceListChanged` | 서버 리소스 변경(문서 인덱싱 완료 등) 시 감지합니다. |
| `@McpPromptListChanged` | 서버 프롬프트 변경 시 감지합니다. |
| `@McpSampling` | 서버가 LLM 추론을 요청할 때 Ollama에 위임합니다. `uploadAndIndexDocument`의 문서 요약 요청이 이 경로로 처리됩니다. |

---

## REST API

### 채팅

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/chat/rag/stream` | RAG 채팅 스트리밍 (SSE). 파라미터: `message`, `sessionId`, `model` |
| `GET` | `/api/chat/simple/stream` | 일반 채팅 스트리밍 (SSE). 파라미터: `message`, `sessionId`, `model` |

### 세션 관리

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/chat/sessions` | 새 세션 생성 |
| `GET` | `/api/chat/sessions` | 세션 목록 조회 |
| `GET` | `/api/chat/sessions/{id}` | 특정 세션 조회 |
| `GET` | `/api/chat/sessions/{id}/messages` | 세션의 메시지 목록 조회 |
| `PUT` | `/api/chat/sessions/{id}/title` | 세션 제목 변경 |
| `DELETE` | `/api/chat/sessions/{id}` | 세션 삭제 |

### 문서 및 모델

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/documents/upload` | 파일 업로드 후 MCP 임베딩. 완료까지 대기 후 결과 반환. |
| `POST` | `/api/documents/index-local` | MCP 서버 로컬 파일 인덱싱 요청 |
| `GET` | `/api/ollama/models` | 현재 Ollama에 설치된 모델 목록 조회 |

---

## 프로젝트 구조

```
my-webflux-mcp-client/
└── src/main/
    ├── java/com/example/client/
    │   ├── ClientApplication.java
    │   ├── config/
    │   │   ├── ChatClientConfig.java          # ChatClient, JDBC ChatMemory, MessageChatMemoryAdvisor 설정
    │   │   ├── OllamaConfig.java              # Ollama ChatModel 설정
    │   │   ├── SwaggerConfig.java
    │   │   └── EgovCommonConfig.java
    │   ├── context/
    │   │   └── SessionContext.java            # ThreadLocal 세션 컨텍스트 (보조용)
    │   ├── controller/
    │   │   ├── ChatController.java            # /api/chat/rag/stream, /simple/stream
    │   │   ├── ChatPageController.java        # / → chat.html
    │   │   ├── ChatSessionController.java     # 세션 CRUD API
    │   │   ├── DocumentUploadController.java  # 파일 업로드 → MCP 도구 호출
    │   │   ├── OllamaModelController.java     # Ollama 모델 목록
    │   │   └── UploadPageController.java      # /upload → upload.html
    │   ├── dto/
    │   │   ├── ChatSessionDto.java
    │   │   └── ChatMessageDto.java
    │   ├── entity/
    │   │   └── ChatSessionEntity.java         # JPA 엔티티 (spring_ai_chat_sessions)
    │   ├── handler/
    │   │   └── McpClientEventHandler.java     # @McpLogging, @McpSampling 등 MCP 이벤트 처리
    │   ├── repository/
    │   │   └── ChatSessionRepository.java
    │   └── service/
    │       ├── ChatService.java
    │       ├── ChatSessionService.java
    │       ├── OllamaModelService.java
    │       └── impl/
    │           ├── ChatServiceImpl.java        # RAG(강제 선호출) / Simple(LLM 자율) 구현
    │           ├── ChatSessionServiceImpl.java
    │           └── OllamaModelServiceImpl.java
    └── resources/
        ├── application.yml
        ├── static/js/marked.min.js            # 마크다운 렌더링 라이브러리
        └── templates/
            ├── chat.html                      # 채팅 UI
            └── upload.html                    # 문서 업로드 UI
```

---

## 사전 준비

### 1. MCP 서버 실행

이 클라이언트는 my-webflux-mcp-server가 먼저 실행 중이어야 합니다.

```bash
cd C:/workspace-team/my-webflux-mcp-server
mvn clean package -DskipTests
java -jar target/my-webflux-mcp-server-*.jar
```

### 2. PostgreSQL (chatdb)

세션 정보와 채팅 메모리를 저장하는 전용 DB입니다. MCP 서버의 ragdb(5432)와 별개입니다.

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

---

## 빌드 및 실행

```bash
cd C:/workspace-team/my-webflux-mcp-client
mvn clean package -DskipTests
java -jar target/my-webflux-mcp-client-*.jar
```

---

## 접속 URL

| 용도 | URL |
|------|-----|
| 채팅 UI | http://localhost:8080 |
| 문서 업로드 UI | http://localhost:8080/upload |
| Swagger UI | http://localhost:8080/swagger-ui.html |

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
        request-timeout: 60000   # MCP 요청 타임아웃 (ms)
    chat.memory.repository.jdbc:
      initialize-schema: always  # 최초 실행 시 테이블 자동 생성
server:
  port: 8080
```

---

## 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| MCP 서버 연결 실패 | my-webflux-mcp-server 미실행 | 서버를 9090 포트로 먼저 기동 |
| chatdb 연결 오류 | PostgreSQL 미실행 또는 포트 오류 | 5433 포트로 Docker 컨테이너 확인 |
| MCP 도구 목록이 1개 (`uploadAndIndexDocument`만 표시됨) | 서버 도구가 `String` 반환 (ASYNC 서버 비호환) | 서버 도구 메서드 반환 타입을 `Mono<String>`으로 변경 |
| RAG 결과가 "관련 문서 없음" | 벡터 DB에 인덱싱된 문서 없음 | `/upload`에서 파일 업로드 또는 서버 재인덱싱 |
| 세션 ID가 null / default로 처리 | 프론트엔드에서 세션 미선택 | 페이지 로드 시 자동으로 첫 번째 세션을 선택하도록 `initializeSessionManagement()` 처리됨 |
| 문서 업로드 타임아웃 | 대용량 파일 처리 시간 초과 | `request-timeout` 값을 늘려서 대응 (예: 120000ms) |
| Ollama 모델 목록 비어 있음 | Ollama 미실행 | `ollama serve` 실행 후 재시도 |
