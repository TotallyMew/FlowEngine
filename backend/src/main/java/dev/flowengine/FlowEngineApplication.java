package dev.flowengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class FlowEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlowEngineApplication.class, args);
    }
}
