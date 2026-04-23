package com.securitysystem;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import javax.sql.DataSource;
import java.sql.Connection;

@SpringBootApplication
public class SecuritySystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecuritySystemApplication.class, args);
    }

    @Bean
    public CommandLineRunner debugConnection(DataSource dataSource) {
        return args -> {
            try (Connection conn = dataSource.getConnection()) {
                System.out.println("=========================================");
                System.out.println("DEBUG: Connected to -> " + conn.getMetaData().getURL());
                System.out.println("DEBUG: Database User -> " + conn.getMetaData().getUserName());
                System.out.println("=========================================");
            } catch (Exception e) {
                System.err.println("DEBUG: Could not connect to database: " + e.getMessage());
            }
        };
    }
}