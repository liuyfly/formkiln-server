package io.formkiln.server.storage;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Selects the file storage: {@code FORMKILN_STORAGE=filesystem} (default) or {@code s3}. */
@Configuration
public class StorageConfig {

    @Bean
    ObjectStorage objectStorage(
            @Value("${formkiln.storage.type:filesystem}") String type,
            @Value("${formkiln.storage.path:./data/files}") String path,
            @Value("${formkiln.storage.s3.bucket:}") String bucket,
            @Value("${formkiln.storage.s3.region:us-east-1}") String region,
            @Value("${formkiln.storage.s3.endpoint:}") String endpoint,
            @Value("${formkiln.storage.s3.path-style:false}") boolean pathStyle,
            @Value("${formkiln.storage.s3.access-key:}") String accessKey,
            @Value("${formkiln.storage.s3.secret-key:}") String secretKey) {
        return switch (type) {
            case "filesystem" -> new FileSystemObjectStorage(Path.of(path));
            case "s3" -> {
                if (bucket.isBlank()) {
                    throw new IllegalStateException("FORMKILN_S3_BUCKET is required when FORMKILN_STORAGE=s3");
                }
                var builder = S3Client.builder()
                        .httpClient(UrlConnectionHttpClient.create())
                        .region(Region.of(region))
                        .forcePathStyle(pathStyle)
                        .credentialsProvider(accessKey.isBlank()
                                ? DefaultCredentialsProvider.builder().build()
                                : StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
                if (!endpoint.isBlank()) {
                    builder.endpointOverride(URI.create(endpoint));
                }
                yield new S3ObjectStorage(builder.build(), bucket);
            }
            default -> throw new IllegalStateException("FORMKILN_STORAGE must be filesystem or s3");
        };
    }
}
