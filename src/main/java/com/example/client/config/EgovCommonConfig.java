package com.example.client.config;

import org.egovframe.rte.fdl.cmmn.trace.LeaveaTrace;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

/**
 * egovframe 공통 설정
 */
@Configuration
public class EgovCommonConfig {

    @Bean(name = "leaveaTrace")
    public LeaveaTrace leaveaTrace() {
        return new LeaveaTrace();
    }
    
}
