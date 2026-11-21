package com.example.ckd_prediction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.context.annotation.Import(com.ckd.auth.SecurityAutoConfiguration.class)
@SpringBootApplication(scanBasePackages = {
        "com.example.ckd_prediction",
        "Controllers",
        "Database",
        "services"
})
public class CkdPredictionApplication {

    public static void main(String[] args) {
        SpringApplication.run(CkdPredictionApplication.class, args);
    }

}
