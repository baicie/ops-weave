package com.acme.opsweave.ingestion;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(excludeName = {
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
public class IngestionApplication {
    public static void main(String[] args) { SpringApplication.run(IngestionApplication.class, args); }
}
