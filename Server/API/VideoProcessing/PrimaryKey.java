package api.videoProcessing;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBHashKey;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBRangeKey;

public class PrimaryKey {
    @DynamoDBHashKey 
    private String userId;

    @DynamoDBRangeKey
    private String videoProcessingId;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String newUser) {
        userID = newUser;
    }

    public String getVideoProcessingId() {
        return videoProcessingId;
    }

    public void setVideoProcessingId(String newVideoProcessingId) {
        videoProcessingId = newVideoProcessingId;
    }
}