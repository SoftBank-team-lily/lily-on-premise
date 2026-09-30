package com.lily.onpremise;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
@ConfigurationPropertiesScan
public class OnPremiseApplication {

    public static void main(String[] args) {
        SpringApplication.run(OnPremiseApplication.class, args);
    }
}
