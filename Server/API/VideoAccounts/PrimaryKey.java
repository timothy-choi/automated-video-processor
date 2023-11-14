package api.VideoAccounts;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBHashKey;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBRangeKey;

public class PrimaryKey {
    @DynamoDBHashKey 
    private String userId;

    @DynamoDBRangeKey
    private String videoAccountId;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String newUser) {
        userID = newUser;
    }

    public String getVideoAccountId() {
        return videoAccountId;
    }

    public void setVideoAccountId(String newVideoAccountId) {
        videoAccountId = newVideoAccountId;
    }
}
