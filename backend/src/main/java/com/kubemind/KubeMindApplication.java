package com.kubemind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KubeMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(KubeMindApplication.class, args);
    }
}
