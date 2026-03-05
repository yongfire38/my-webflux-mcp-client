-- 채팅 세션 관리를 위한 테이블 생성
CREATE TABLE IF NOT EXISTS spring_ai_chat_sessions (
    session_id VARCHAR(255) PRIMARY KEY,
    title VARCHAR(500) NOT NULL DEFAULT '새 채팅',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_message_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 인덱스 생성
CREATE INDEX IF NOT EXISTS idx_chat_sessions_last_message_at ON spring_ai_chat_sessions(last_message_at);
CREATE INDEX IF NOT EXISTS idx_chat_sessions_created_at ON spring_ai_chat_sessions(created_at);
