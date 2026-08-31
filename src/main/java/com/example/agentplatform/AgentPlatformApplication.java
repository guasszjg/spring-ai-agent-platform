package com.example.agentplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);
        System.out.println("==========================================================");
        System.out.println("  🤖 Spring AI 智能体管理平台 已成功启动!");
        System.out.println("  🌐 访问地址: http://localhost:8080");
        System.out.println("  🔑 默认体验账号: admin / admin123");
        System.out.println("==========================================================");
    }
}
