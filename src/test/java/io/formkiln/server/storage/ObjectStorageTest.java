package io.formkiln.server.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Testcontainers
class ObjectStorageTest {

    @Container
    static final GenericContainer<?> S3 = new GenericContainer<>("adobe/s3mock:5.2.3").withExposedPorts(9090);

    @TempDir
    Path directory;

    private static void roundTrip(ObjectStorage storage) throws IOException {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        storage.put("forms/a/b", new ByteArrayInputStream(data), data.length, "text/plain");
        try (InputStream in = storage.get("forms/a/b")) {
            assertThat(in.readAllBytes()).isEqualTo(data);
        }
        storage.delete("forms/a/b");
        assertThatThrownBy(() -> storage.get("forms/a/b").close()).isInstanceOf(IOException.class);
    }

    @Test
    void fileSystemStorage() throws IOException {
        roundTrip(new FileSystemObjectStorage(directory));
    }

    @Test
    void fileSystemStorageRejectsKeysOutsideItsRoot() {
        FileSystemObjectStorage storage = new FileSystemObjectStorage(directory);
        assertThatThrownBy(() -> storage.put("../escape", new ByteArrayInputStream(new byte[1]), 1, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void s3Storage() throws IOException {
        S3Client client = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .endpointOverride(URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(9090)))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        client.createBucket(b -> b.bucket("formkiln"));
        roundTrip(new S3ObjectStorage(client, "formkiln"));
    }
}
