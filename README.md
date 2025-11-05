# WebFlux MCP Client

Spring AI MCP Client with WebFlux (Reactive) and Ollama LLM 샘플 프로젝트입니다.

WebFlux MCP 서버의 Tool을 사용하여 로컬 LLM이 외부 API 데이터를 활용할 수 있도록 합니다.

## 특징

- **Reactive Stack**: Spring WebFlux 기반 완전 논블로킹 아키텍처
- **비동기 처리**: Mono/Flux를 사용한 리액티브 프로그래밍
- **고성능**: 적은 스레드로 많은 동시 요청 처리
- **MCP Tool Integration**: 원격 MCP 서버의 Tool을 LLM이 동적으로 사용
- **Production Ready**: Spring AI 공식 권장 방식

## 동작 흐름 (Reactive)

```
[사용자]
   ↓
[REST API /api/chat] (Reactive)
   ↓
[ChatController] → Mono<ChatResponse>
   ↓
[ChatService] → Mono<String>
   ↓ (subscribeOn: boundedElastic)
[Ollama LLM] ←→ [AsyncMcpToolCallbackProvider] ←→ [MCP Server (http://localhost:9090)]
   ↓ (블로킹 작업을 별도 스레드에서 실행)     ↓
[Mono 응답]                              [Tools: getTouristWeatherIndex, getCityInfo, getCurrentTime]
```

## 프로젝트 구조

```
my-webflux-mcp-client/
├── pom.xml                                    # Maven 설정 (WebFlux, MCP Client)
├── README.md
└── src/main/
    ├── java/com/example/client/
    │   ├── ClientApplication.java             # Main Application
    │   ├── config/
    │   │   ├── OllamaConfig.java              # Ollama API 설정
    │   │   └── SwaggerConfig.java             # Swagger 설정
    │   ├── controller/
    │   │   └── ChatController.java            # Reactive REST API (Mono 반환)
    │   └── service/
    │       └── ChatService.java               # Reactive LLM + MCP Tool 통합
    └── resources/
        └── application.properties             # Ollama, MCP 서버, 타임아웃 설정
```

## 사전 준비

### 1. Ollama 설치 및 모델 다운로드

```bash
# Ollama 설치 (https://ollama.com/download)

# 모델 다운로드 (Tool Calling 지원 모델 필요)
ollama pull qwen2.5:7b

# Ollama 서버 실행 확인
ollama serve
```

Ollama 서버가 http://localhost:11434 에서 실행 중이어야 합니다.

### 2. MCP 서버 실행

먼저 WebFlux MCP 서버를 실행해야 합니다:

```bash
cd C:\workspace-test\webflux-mcp-sample\my-webflux-mcp-server
mvn clean package
java -jar target/webflux-mcp-0.0.1-SNAPSHOT.jar
```

서버가 http://localhost:9090 에서 실행되고 있어야 합니다.

## 빌드 및 실행

### 1. 빌드
```bash
cd C:\workspace-test\webflux-mcp-sample\my-webflux-mcp-client
mvn clean package
```

### 2. 실행
```bash
java -jar target/webflux-mcp-client-0.0.1-SNAPSHOT.jar
```

클라이언트가 http://localhost:8080 에서 실행됩니다.

**시작 로그 확인:**
```
INFO  o.s.b.w.e.netty.NettyWebServer  : Netty started on port 8080
                                        ^^^^^^ WebFlux는 Netty 사용
INFO  c.e.c.ClientApplication         : Started ClientApplication
```

## 테스트

### 테스트 시나리오

다음 흐름으로 MCP Client가 정상 동작하는지 확인합니다:

1. **클라이언트에 질문** → REST API로 질문 전송
2. **로컬 LLM이 판단** → Tool 필요 여부 결정
3. **MCP 서버의 Tool 호출** → 서버에서 공공 API 데이터 조회
4. **응답 확인** → LLM이 Tool 결과를 활용해 답변 생성

### 1. 관광지 날씨 조회 테스트

```bash
# GET 방식
curl "http://localhost:8080/api/chat?message=서울 강남구 관광지 날씨 알려줘"

# POST 방식
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "제주도 관광지 TCI 지수는?"}'
```

**예상 동작**:
1. Ollama LLM이 질문 분석
2. `getCityInfo` Tool로 도시 코드 확인
3. `getTouristWeatherIndex` Tool이 필요하다고 판단
4. MCP 서버에 Tool 호출 요청
5. 공공 API에서 관광지 기후 지수 데이터 조회
6. LLM이 결과를 자연어로 응답

### 2. 시간 조회 테스트

```bash
curl "http://localhost:8080/api/chat?message=서울 시간대의 현재 시간은?"

# 또는
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Asia/Seoul 타임존의 현재 날짜와 시간 알려줘"}'
```

**예상 동작**:
1. Ollama LLM이 질문 분석
2. `getCurrentDateTimeWithZone` Tool이 필요하다고 판단
3. MCP 서버에 Tool 호출 요청
4. 서버에서 현재 시간 조회
5. LLM이 결과를 자연어로 응답

### 3. 로그 확인

클라이언트 콘솔에서 다음 로그를 확인할 수 있습니다:

```
INFO  c.e.c.service.ChatService - User message: 서울 강남구 관광지 날씨 알려줘
INFO  c.e.c.service.ChatService - Available tools: [getTouristWeatherIndex, getTouristWeatherByDate, getCityInfo, getCurrentDateTimeWithZone]
INFO  c.e.c.service.ChatService - AI response: ...
```

## 테스트 성공 기준

✅ **성공적인 테스트**:
1. MCP Client가 서버에 연결되어 Tool 목록을 가져옴
2. 사용자 질문에 대해 LLM이 적절한 Tool을 선택
3. MCP 서버의 Tool이 호출되어 실제 데이터 반환
4. LLM이 Tool 결과를 활용하여 자연어 응답 생성

## 주요 설정

### application.properties

```properties
# Ollama LLM 설정
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.chat.options.model=qwen3-4b:Q4_K_M
spring.ai.ollama.chat.options.temperature=0.7

# Ollama 타임아웃 설정 (Tool Calling은 시간이 오래 걸림)
spring.ai.ollama.chat.timeout=120s
spring.ai.retry.max-attempts=3

# MCP Client Type (ASYNC for WebFlux)
spring.ai.mcp.client.type=ASYNC

# MCP Client 연결 설정
spring.ai.mcp.client.sse.connections.webflux-weather-api.url=http://localhost:9090/mcp/sse
spring.ai.mcp.client.init-timeout=60000
```

### 다른 LLM 모델 사용

Ollama의 다른 모델을 사용하려면 (Tool Calling 지원 모델만 가능):

```bash
# Tool Calling 지원 모델 다운로드
ollama pull llama3.1
ollama pull mistral

# application.properties 수정
spring.ai.ollama.chat.options.model=llama3.1
```

**주의**: Tool Calling을 사용하려면 지원하는 모델(Llama 3.1+, Mistral, Qwen 등)을 사용해야 합니다.

## 트러블슈팅

### 1. "Connection refused" 오류

**원인**: MCP 서버가 실행되지 않음

**해결**:
```bash
cd C:\workspace-test\webflux-mcp-sample\my-webflux-mcp-server
java -jar target/webflux-mcp-0.0.1-SNAPSHOT.jar
```

### 2. "Ollama not available" 오류

**원인**: Ollama 서버가 실행되지 않음

**해결**:
```bash
ollama serve
```

### 3. Tool이 호출되지 않음

**원인**: LLM이 Tool이 필요하다고 판단하지 못함

**해결**: 질문을 더 구체적으로 변경
- ❌ "날씨 어때?"
- ✅ "서울 강남구 관광지 날씨 알려줘"
- ✅ "제주도 관광지 TCI 지수는?"

## 확장 아이디어

1. **Web UI 추가**: React, Vue 등으로 채팅 인터페이스 구현
2. **대화 히스토리**: 이전 대화 내용을 기억하는 기능
3. **다중 MCP 서버**: 여러 MCP 서버 동시 연결
4. **스트리밍 응답**: SSE로 실시간 응답 스트리밍

## WebFlux Reactive 특징

### Reactive Programming

```java
// ChatController (Reactive)
@PostMapping
public Mono<ChatResponse> chat(@RequestBody ChatRequest request) {
    return chatService.chat(request.message())
        .map(ChatResponse::new);  // 논블로킹 변환
}

// ChatService (Reactive)
public Mono<String> chat(String userMessage) {
    return Mono.fromCallable(() -> {
        // 블로킹 작업 (Ollama LLM 호출)
        return chatModel.call(prompt);
    })
    .subscribeOn(Schedulers.boundedElastic())  // 별도 스레드에서 실행
    .map(response -> response.replaceAll("<think>.*?</think>", ""));
}
```

### subscribeOn(Schedulers.boundedElastic())의 역할

- **문제**: Ollama LLM 호출은 블로킹 작업
- **WebFlux 제약**: 이벤트 루프 스레드에서 블로킹 금지
- **해결**: `boundedElastic()` 스레드 풀에서 블로킹 작업 실행

```
요청 → WebFlux 이벤트 루프 (reactor-http-nio-6)
         ↓
     subscribeOn(boundedElastic)
         ↓
     별도 스레드 (boundedElastic-1)
         ↓ (여기서 블로킹 가능!)
     chatModel.call(prompt)
         ↓
     WebFlux로 결과 반환
```

## WebMVC vs WebFlux 비교

| 항목 | WebMVC | WebFlux (현재) |
|------|--------|----------------|
| **처리 모델** | 동기/블로킹 | 비동기/논블로킹 |
| **프로그래밍** | 명령형 | 리액티브 (Mono/Flux) |
| **스레드** | 요청당 1 스레드 | 이벤트 루프 (4~8 스레드) |
| **의존성** | `spring-boot-starter-web` | `spring-boot-starter-webflux` |
| **서버** | Tomcat | Netty |
| **MCP Client** | `spring-ai-starter-mcp-client` | `spring-ai-starter-mcp-client-webflux` |
| **성능** | 동시 100명까지 적합 | 동시 1000명+ 처리 가능 |
| **학습 곡선** | 낮음 | 높음 (Reactive) |
| **Spring AI 권장** | 개발/테스트 | **프로덕션** |

### WebFlux의 장점
- 적은 메모리로 많은 동시 요청 처리
- 논블로킹 I/O로 높은 처리량
- 백프레셔(backpressure) 자동 처리
- Spring AI 공식 권장 방식

## 주요 의존성

### pom.xml

```xml
<!-- Spring Boot WebFlux -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- Spring AI MCP Client (WebFlux) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client-webflux</artifactId>
</dependency>

<!-- Spring AI Ollama -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>

<!-- Springdoc OpenAPI (WebFlux) -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
</dependency>
```

## 성능 특성

### 예상 응답 시간

| 작업 | 시간 | 설명 |
|------|------|------|
| 단순 질문 (Tool 없음) | 2~5초 | LLM 추론만 |
| Tool 1회 호출 | 5~15초 | getCityInfo 등 |
| Tool 2~3회 호출 | 15~30초 | 다단계 Tool 호출 |

### 타임아웃 설정

```properties
# Ollama LLM 타임아웃 (120초)
spring.ai.ollama.chat.timeout=120s

# MCP Client 초기화 타임아웃 (60초)
spring.ai.mcp.client.init-timeout=60000
```

## 트러블슈팅

### 1. ReadTimeoutException

**증상**: `io.netty.handler.timeout.ReadTimeoutException`

**원인**: Tool Calling이 타임아웃보다 오래 걸림

**해결**:
```properties
spring.ai.ollama.chat.timeout=180s  # 3분으로 증가
```

### 2. IllegalStateException: block() not supported

**증상**: `block()/blockFirst()/blockLast() are blocking`

**원인**: WebFlux 이벤트 루프 스레드에서 블로킹 호출

**해결**: `subscribeOn(Schedulers.boundedElastic())` 사용 (이미 적용됨)

### 3. Swagger UI 404

**경로 확인**:
- `/swagger-ui.html`
- `/webjars/swagger-ui/index.html`

**의존성 확인**:
```xml
<!-- WebFlux용 Swagger -->
<artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
```
