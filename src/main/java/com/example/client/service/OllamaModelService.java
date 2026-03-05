package com.example.client.service;

import java.util.List;

/**
 * Ollama 모델 관리 서비스 인터페이스
 */
public interface OllamaModelService {

    boolean isOllamaAvailable();

    List<String> getInstalledModels();
}
