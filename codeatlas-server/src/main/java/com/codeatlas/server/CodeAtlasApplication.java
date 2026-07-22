package com.codeatlas.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.codeatlas")
public class CodeAtlasApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeAtlasApplication.class, args);
    }
}
