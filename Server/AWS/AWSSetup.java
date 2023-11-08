package AWS;


import com.amazonaws.auth.BasicAWSCredentials;


public class AWSSetup {
    public static BasicAWSCredentials createAWSClient() {
        String accessKey = System.getenv("accessKey");
        String secretKey = System.getenv("secretKey");
        BasicAWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);

        return credentials;
    }
}
