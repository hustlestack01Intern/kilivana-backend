package com.kilivana;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KilivanaBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(KilivanaBackendApplication.class, args);
    }
}
