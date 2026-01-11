package com.secp.api.it;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;

@Testcontainers
public abstract class IntegrationTestBase {

  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
      .withDatabaseName("secp")
      .withUsername("postgres")
      .withPassword("postgres");

  static final GenericContainer<?> redis = new GenericContainer<>("redis:7")
      .withExposedPorts(6379);

  static final GenericContainer<?> minio = new GenericContainer<>("minio/minio:RELEASE.2024-12-18T13-15-44Z")
      .withEnv("MINIO_ROOT_USER", "minio")
      .withEnv("MINIO_ROOT_PASSWORD", "minio123456")
      .withCommand("server /data")
      .withExposedPorts(9000);

  static final String s3Bucket = "secp-it";

  static {
    logDockerAvailabilityOnce();
    postgres.start();
    redis.start();
    minio.start();

    // Create bucket for tests
    String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
    try (S3Client s3 = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("minio", "minio123456")))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build()) {
      s3.createBucket(CreateBucketRequest.builder().bucket(s3Bucket).build());
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", () -> "secp_app");
    r.add("spring.datasource.password", () -> "secp_app");

    r.add("spring.flyway.url", postgres::getJdbcUrl);
    r.add("spring.flyway.user", postgres::getUsername);
    r.add("spring.flyway.password", postgres::getPassword);

    r.add("spring.data.redis.host", redis::getHost);
    r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

    r.add("secp.s3.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
    r.add("secp.s3.region", () -> "us-east-1");
    r.add("secp.s3.bucket", () -> s3Bucket);
    r.add("secp.s3.access-key", () -> "minio");
    r.add("secp.s3.secret-key", () -> "minio123456");
    r.add("secp.s3.path-style", () -> "true");
  }

  private static void logDockerAvailabilityOnce() {
    try {
      System.out.println("[IT] Testcontainers Docker available: " + DockerClientFactory.instance().isDockerAvailable());
    } catch (Exception e) {
      System.out.println("[IT] Testcontainers Docker availability check failed: " + e.getMessage());
    }
  }
}
