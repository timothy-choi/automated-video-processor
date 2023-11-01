package api.templates;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBHashKey;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBRangeKey;

public class PrimaryKey {
    @DynamoDBHashKey 
    private String userId;

    @DynamoDBRangeKey
    private String templateId;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String newUser) {
        userID = newUser;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String newTemplate) {
        templateId = newTemplate;
    }
}
