package com.familyhome.file.storage;

import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

/**
 * S3 兼容对象存储（MinIO、OSS、COS 等）。使用路径式访问与显式 endpoint，
 * 便于私有部署；凭据仅来自环境变量注入的配置。
 */
@Component
@EnableConfigurationProperties(FileStorageProperties.class)
@ConditionalOnProperty(name = "family.file.storage.type", havingValue = "s3")
public class S3FileStorage implements FileStorage {
    @Autowired private FileStorageProperties properties;
    private S3Client client;
    private String bucket;

    @PostConstruct
    void initialize() {
        FileStorageProperties.S3 cfg = properties.getS3();
        if (cfg.getBucket() == null || cfg.getBucket().isBlank())
            throw new IllegalStateException("family.file.storage.s3.bucket 必须配置");
        if (cfg.getEndpoint() == null || cfg.getEndpoint().isBlank())
            throw new IllegalStateException("family.file.storage.s3.endpoint 必须配置");
        client =
                S3Client.builder()
                        .region(Region.of(cfg.getRegion()))
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey())))
                        .endpointOverride(URI.create(cfg.getEndpoint()))
                        .serviceConfiguration(
                                S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .build();
        bucket = cfg.getBucket();
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } else {
                throw ex;
            }
        }
    }

    @Override
    public void save(String id, InputStream content, long size) {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(id)
                        .contentLength(size)
                        .contentType("application/octet-stream")
                        .build(),
                RequestBody.fromInputStream(content, size));
    }

    @Override
    public Resource load(String id) throws IOException {
        GetObjectResponse response;
        InputStream stream;
        try {
            GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(id).build();
            var result = client.getObject(request);
            response = result.response();
            stream = result;
        } catch (NoSuchKeyException ex) {
            throw new IOException("对象不存在: " + id, ex);
        }
        Objects.requireNonNull(response);
        return new InputStreamResource(stream);
    }

    @Override
    public void delete(String id) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(id).build());
    }
}
