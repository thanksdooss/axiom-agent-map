package com.axiom.agentmap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AxiomApplication {
    public static void main(String[] args) {
        SpringApplication.run(AxiomApplication.class, args);
    }
}
