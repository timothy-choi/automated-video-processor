package api.videoProcessing;

import com.amazonaws.services.dynamodbv2.datamodeling.*;
import org.springframework.data.annotation.Id;

import api.videoProcessing.PrimaryKey;

import java.util.*;

@DynamoDBTable(tableName = "videoProcessing")
public class VideoProcessing {
    @Id
    private PrimaryKey key;

    @DynamoDBAttribute(name = "name")
    private String name;

    @DynamoDBAttribute(name = "creator")
    private String creator;

    @DynamoDBAttribute(name = "templateId")
    private String templateId;

    @DynamoDBAttribute(name = "templatePartitions")
    private List<Pair<Int, Int>> templatePartitions;

    @DynamoDBAttribute(name = "importedVideos")
    private List<String> importedVideos;

    @DynamoDBAttribute(name = "videoOrder")
    private List<String> videoOrder;

    @DynamoDBAttribute(name = "slideAnimations")
    private List<String> slideAnimations;
}
