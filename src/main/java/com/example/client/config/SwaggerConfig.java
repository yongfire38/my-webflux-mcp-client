package com.example.client.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("MCP Client API")
                .version("1.0.0")
                .description("MCP Client with Ollama LLM. " +
                    "이 클라이언트는 로컬 LLM(Ollama)과 원격 MCP 서버의 Tool을 연결합니다.")
                .contact(new Contact()
                    .name("MCP Client")
                    .url("http://localhost:8080")));
    }
}
