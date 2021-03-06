/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.object.s3.aws;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;
import com.amazonaws.util.StringInputStream;
import com.emc.object.s3.S3Config;
import com.emc.util.TestConfig;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AwsTest {
    private static final String PROP_S3_ENDPOINT = "s3.endpoint";
    public static final String PROP_S3_ACCESS_KEY = "s3.access_key";
    public static final String PROP_S3_SECRET_KEY = "s3.secret_key";

    private static EcsAwsAdapter s3;
    private Map<String, Set<String>> bucketsAndKeys = new TreeMap<String, Set<String>>();

    @Test
    public void testCrudBuckets() throws Exception {
        String bucket = "test-crud-bucket";
        s3.createBucket(bucket);
        Assert.assertTrue("created bucket does not exist", s3.doesBucketExist(bucket));

        boolean bucketFound = false;
        List<Bucket> buckets = s3.listBuckets();
        for (Bucket b : buckets) {
            if (b.getName().equals(bucket)) bucketFound = true;
        }
        Assert.assertTrue("bucket not found in listBuckets", bucketFound);

        s3.deleteBucket(bucket);

        Assert.assertFalse("bucket still exists after delete", s3.doesBucketExist(bucket));

        bucketFound = false;
        buckets = s3.listBuckets();
        for (Bucket b : buckets) {
            if (b.getName().equals(bucket)) bucketFound = true;
        }
        Assert.assertFalse("bucket found in listBuckets after delete",
                bucketFound);
    }

    @Test
    public void testDeleteNonEmptyBucket() throws Exception {
        String bucket = "test-nonempty-bucket";
        String content = "Hello World";
        String key = "testKey";

        s3.createBucket(bucket);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setHeader(key, content);
        metadata.setContentLength(content.length());
        s3.putObject(bucket, key, new StringInputStream(content), metadata);
        createdKeys(bucket).add(key);

        try {
            s3.deleteBucket(bucket);
            Assert.fail("deleting non-empty bucket should fail");
        } catch (Exception e) {
            Assert.assertTrue(true);
        }
    }

    @Test
    public void testCrudKeys() throws Exception {
        String bucket = "test-crudkey-bucket";
        String content = "Hello World";
        String key = "testKey";

        s3.createBucket(bucket);
        createdKeys(bucket); // make sure we clean up the bucket
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setHeader(key, content);
        metadata.setContentLength(content.length());

        s3.putObject(bucket, key, new StringInputStream(content), metadata);

        S3Object object = s3.getObject(bucket,key);
        String readContent = new Scanner(object.getObjectContent(), "UTF-8").useDelimiter("\\A").next();
        Assert.assertEquals("content mismatch", content, readContent);

        String newContent = "Goodbye World";
        metadata.setContentLength(newContent.length());
        s3.putObject(bucket, key, new StringInputStream(newContent), metadata);

        object = s3.getObject(bucket, key);
        readContent = new Scanner(object.getObjectContent(), "UTF-8").useDelimiter("\\A").next();
        Assert.assertEquals("updated content mismatch", newContent, readContent);

        System.out.println("bob");

        s3.deleteObject(bucket, key);

        try {
            s3.getObject(bucket, key);
            Assert.fail("object still exists after delete");
        } catch (Exception e) {
            Assert.assertTrue(true);
        }
    }

    @Test
    public void testUpdateMetadata() throws Exception {
        String bucket = "meta-bucket";
        String key = "metaKey";
        String content = "test metadata update";

        s3.createBucket(bucket);
        createdKeys(bucket);

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.addUserMetadata("key1", "value1");
        metadata.setContentLength(content.length());
        s3.putObject(bucket, key, new StringInputStream(content), metadata);
        createdKeys(bucket).add(key);

        // verify metadata
        S3Object object = s3.getObject(bucket, key);
        metadata = object.getObjectMetadata();
        Assert.assertEquals("value1", metadata.getUserMetadata().get("key1"));

        // update (add) metadata - only way is to copy
        metadata.addUserMetadata("key2", "value2");
        CopyObjectRequest request = new CopyObjectRequest(bucket, key, bucket, key);
        request.setNewObjectMetadata(metadata);
        s3.copyObject(request);

        // verify metadata (both keys)
        object = s3.getObject(bucket, key);
        metadata = object.getObjectMetadata();
        Assert.assertEquals("value1", metadata.getUserMetadata().get("key1"));
        Assert.assertEquals("value2", metadata.getUserMetadata().get("key2"));

        // test for bug 28668
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        copyStream(object.getObjectContent(), baos);
        Assert.assertEquals(content, baos.toString("UTF-8"));
    }

    @Test
    public void testMultipartUpload() throws Exception {
        String bucket = "multipart-bucket";
        String key = "multipartKey";

        // write large file (must be a file to support parallel uploads)
        File tmpFile = File.createTempFile("random", "bin");
        tmpFile.deleteOnExit();
        int objectSize = 100 * 1024 * 1024; // 100M

        copyStream(new ByteArrayInputStream(new byte[objectSize]), new FileOutputStream(tmpFile));

        Assert.assertEquals("tmp file is not the right size", objectSize, tmpFile.length());

        s3.createBucket(bucket);
        createdKeys(bucket);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(10, 10, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(50));
        TransferManager tm = new TransferManager(s3, executor);

        PutObjectRequest request = new PutObjectRequest(bucket, key, tmpFile);
        request.setMetadata(new ObjectMetadata());
        request.getMetadata().addUserMetadata("selector", "one");
        request.getMetadata().setContentLength(objectSize);

        Upload upload = tm.upload(request);
        createdKeys(bucket).add(key);

        upload.waitForCompletion();

        S3Object object = s3.getObject(bucket, key);

        int size = copyStream(object.getObjectContent(), null);
        Assert.assertEquals("Wrong object size", objectSize, size);
    }

    @After
    public void after() {
        for (String bucket : bucketsAndKeys.keySet()) {
            for (String key : createdKeys(bucket)) {
                s3.deleteObject(bucket, key);
            }
            s3.deleteBucket(bucket);
        }
    }

    private synchronized Set<String> createdKeys(String bucket) {
        Set<String> keys = bucketsAndKeys.get(bucket);
        if (keys == null) {
            keys = new TreeSet<String>();
            bucketsAndKeys.put(bucket, keys);
        }
        return keys;
    }

    @BeforeClass
    public static void beforeClass() throws Exception {
        Properties props = TestConfig.getProperties();
        String endpoint = TestConfig.getPropertyNotEmpty(props, PROP_S3_ENDPOINT);
        String accessKey = TestConfig.getPropertyNotEmpty(props, PROP_S3_ACCESS_KEY);
        String secret = TestConfig.getPropertyNotEmpty(props, PROP_S3_SECRET_KEY);

        List<URI> uris = parseUris(endpoint);
        String[] hosts = new String[uris.size()];
        for (int i = 0; i < uris.size(); i++) {
            hosts[i] = uris.get(i).getHost();
        }

        S3Config s3Config = new S3Config(com.emc.object.Protocol.valueOf(uris.get(0).getScheme().toUpperCase()), hosts);
        s3Config.withIdentity(accessKey).withSecretKey(secret);

        s3 = new EcsAwsAdapter(s3Config);
    }

    protected static List<URI> parseUris(String endpoints) throws URISyntaxException {
        List<URI> uris = new ArrayList<URI>();
        for (String uri : endpoints.split(",")) {
            uris.add(new URI(uri));
        }
        return uris;
    }

    private static AmazonS3Client createClient(URI endpoint, String proxyHost, int proxyPort,
                                               String uid, String secret) {
        ClientConfiguration config = new ClientConfiguration();
        config.setProtocol(Protocol.valueOf(endpoint.getScheme().toUpperCase()));

        if (proxyHost != null) {
            config.setProxyHost(proxyHost);
            config.setProxyPort(proxyPort);
        }

        AmazonS3Client client = new AmazonS3Client(new BasicAWSCredentials(uid, secret), config);
        client.setEndpoint(endpoint.toString());

        S3ClientOptions options = new S3ClientOptions();
        options.setPathStyleAccess(true);
        client.setS3ClientOptions(options);
        return client;
    }

    private int copyStream(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[100 * 1024];
        int total = 0, read = is.read(buffer);
        while (read >= 0) {
            if (os != null)
                os.write(buffer, 0, read);
            total += read;
            read = is.read(buffer);
        }
        is.close();
        if (os != null)
            os.close();
        return total;
    }
}
