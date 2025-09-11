package com.example.kyc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(systemName = "KYC Skeleton")
@SpringBootApplication
public class KycApplication {
  public static void main(String[] args) { SpringApplication.run(KycApplication.class, args); }
}
