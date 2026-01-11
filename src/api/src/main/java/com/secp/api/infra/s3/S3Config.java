package com.secp.api.infra.s3;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {

  @Bean
  public S3Client s3Client(
      @Value("${secp.s3.endpoint:}") String endpoint,
      @Value("${secp.s3.region:us-east-1}") String region,
      @Value("${secp.s3.access-key:}") String accessKey,
      @Value("${secp.s3.secret-key:}") String secretKey,
      @Value("${secp.s3.path-style:true}") boolean pathStyle
  ) {
    var builder = S3Client.builder()
        .httpClient(UrlConnectionHttpClient.create())
        .region(Region.of(region))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());

    if (endpoint != null && !endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint));
    }

    if (accessKey != null && !accessKey.isBlank()) {
      builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
    }

    return builder.build();
  }

  @Bean
  public S3Presigner s3Presigner(
      @Value("${secp.s3.endpoint:}") String endpoint,
      @Value("${secp.s3.region:us-east-1}") String region,
      @Value("${secp.s3.access-key:}") String accessKey,
      @Value("${secp.s3.secret-key:}") String secretKey,
      @Value("${secp.s3.path-style:true}") boolean pathStyle
  ) {
    var builder = S3Presigner.builder()
        .region(Region.of(region))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());

    if (endpoint != null && !endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint));
    }

    if (accessKey != null && !accessKey.isBlank()) {
      builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
    }

    return builder.build();
  }
}
