package com.example.ckd_report_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.example.ckd_report_service",
        "Controllers",
        "Database",
        "services"
})
public class CkdReportServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CkdReportServiceApplication.class, args);
    }

}
