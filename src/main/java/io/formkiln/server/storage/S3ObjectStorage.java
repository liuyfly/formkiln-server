package io.formkiln.server.storage;

import java.io.IOException;
import java.io.InputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/** Stores files in an S3-compatible bucket (AWS S3, Cloudflare R2, MinIO, ...). */
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client client;
    private final String bucket;

    public S3ObjectStorage(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, InputStream content, long length, String contentType) {
        client.putObject(b -> b.bucket(bucket).key(key).contentType(contentType).contentLength(length),
                RequestBody.fromInputStream(content, length));
    }

    @Override
    public InputStream get(String key) throws IOException {
        try {
            return client.getObject(b -> b.bucket(bucket).key(key));
        } catch (NoSuchKeyException e) {
            throw new IOException("Object not found", e);
        }
    }

    @Override
    public void delete(String key) {
        client.deleteObject(b -> b.bucket(bucket).key(key));
    }
}
