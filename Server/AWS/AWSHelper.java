package AWS;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

import javax.annotation.Resource;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import AWS.AWSSetup;


public class AWSHelper {

    private AmazonS3 client;

    public AWSHelper(BasicAWSCredentials credentials) {
        client = AmazonS3ClientBuilder.standard()
            .withRegion(Regions.US_EAST_1)
            .withCredentials(credentials)
            .build();
    }

    public void createNewBucket(String name) {
        client.createS3Bucket(name);
    }

    public void deleteBucket(String name) {
        client.deleteS3Bucket(name);
    }

    public bool checkIfBucketExists(String name) {
        List<Bucket> allBuckets = client.listBuckets();
        for (Bucket bucket : allBuckets) {
            if (bucket.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    public void addObjectIntoBucket(String name, String objKey, MultipartFile video) {
        try {
            ObjectMetadata metadata = new ObjectMetaData();
            metadata.setContentType(video.getContentType());
            metadata.setContentLength(video.getSize());
            PutObjectRequest putReq = new PutObjectRequest(name, objKey, video.getInputStream(), metadata);
            client.putObject(putReq);
        } catch (AmazonS3Exception e) {
            throw new Exception("Object doesn't exist");
        }
    }

    public void deleteObjectFromBucket(String bucket, String objKey) {
        try {
            client.deleteObject(new DeleteObjectRequest(bucket, objKey));
        } catch (AmazonS3Exception e) {
            throw new Exception("Object doesn't exist");
        }
    }

    public void replaceObjectInBucket(String name, String objKey, MultipartFile video) {
        addObjectIntoBucket(name, objKey, video);
    }

    public S3Object getObjectFromBucket(String name, String objKey) {
        try {
            GetObjectRequest newReq = new GetObjectRequest(name, objKey);
            return client.getObject(newReq);
        }
        catch (AmazonS3Exception e) {
            throw new Exception("Object doesn't exist");
        }
    }
    
    public static Resource downloadLargeFile(String bucketName, String key, File destinationFile) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        try (ResponseInputStream<GetObjectResponse> responseInputStream = s3Client.getObject(getObjectRequest);
             OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(destinationFile))) {

            byte[] buffer = new byte[8 * 1024]; // 8KB buffer
            int bytesRead;
            while ((bytesRead = responseInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            return new FileSystemResource(destinationFile);
        } catch (IOException e) {
            return null;
        }
    }

    

    
}
