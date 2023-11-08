package AWS;

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

    public void addObjectIntoBucket(String name, String objKey, String videoPath) {
        try {
            PutObjectRequest putReq = new PutObjectRequest(name, objKey, new File(videoPath));
            client.putObject(putReq);
        } catch (AmazonS3Exception e) {
            throw new Exception("Object doesn't exist");
        }
    }

    public void replaceObjectInBucket(String name, String objKey, String newVideoPath) {
        addObjectIntoBucket(name, objKey, newVideoPath);
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

    

    
}
