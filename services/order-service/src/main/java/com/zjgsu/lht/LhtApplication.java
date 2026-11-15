package com.zjgsu.lht;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class LhtApplication {

    public static void main(String[] args) {
        SpringApplication.run(LhtApplication.class, args);
    }

}
