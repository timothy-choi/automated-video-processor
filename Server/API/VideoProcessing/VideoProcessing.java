package api.videoProcessing;

import com.amazonaws.services.dynamodbv2.datamodeling.*;
import org.springframework.data.annotation.Id;

import api.videoProcessing.PrimaryKey;
import javafx.util.Pair;

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

    @DynamoDBAttribute(name = "imageSlides")
    private List<String> imageSlides;

    @DynamoDBAttribute(name = "templatePartitions")
    private List<Pair<Int, Int>> templatePartitions;

    @DynamoDBAttribute(name = "importedVideos")
    private List<String> importedVideos;

    @DynamoDBAttribute(name = "videoOrder")
    private List<String> videoOrder;

    @DynamoDBAttribute(name = "slideAnimations")
    private List<String> slideAnimations;

    @DynamoDBHashKey(name = "userId")
    @DynamoDBAutoGeneratedKey
    public String getUserId() {
        return key.getUserId();
    }

    public void setUserId() {
        if (key == null) {
            key = new PrimaryKey();
        }
        UUID newUUID = UUID.randomUUID();
        key.setUserId(newUUID.toString());
    }

    @DynamoDBRangeKey(name = "videoProcessingId")
    @DynamoDBAutoGeneratedKey
    public String getVideoProcessingId() {
        return key.getVideoProcessingId();
    }

    public void setVideoProcessingId() {
        if (key == null) {
            key = new PrimaryKey();
        }
        UUID newUUID = UUID.randomUUID();
        key.setVideoProcessingId(newUUID.toString());
    }

    public String getName() {
        return name;
    }

    public void setName(String newName) {
        name = newName;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String maker) {
        creator = maker;
    }

    public String getTemplateId() {
        return templateId;
    }

    public String setTemplateId(String tempId) {
        templateId = tempId;
    }

    public List<String> getAllImageSlides() {
        return imageSlides;
    }

    public void createImageSlides() {
        imageSlides = new ArrayList<String>();
    }

    public void addImageSlide(String imagePath) {
        imageSlides.add(imagePath);
    }

    public void deleteImageSlide(String imagePath) {
        imageSlides.remove(imagePath);
    }

    public List<Pair<Int, Int>> getPartitions() {
        return templatePartitions;
    }

    public void createPartitions() {
        templatePartitons = new ArrayList<Pair<Int, Int>>();
    }

    public void addPartition(Pair<Int, Int> partition) {
        templatePartitions.add(partition);
    }

    public void deletePartition(Pair<Int, Int> partition) {
        templatePartitions.delete(partition);
    }

    public void editPartition(Int index, Int value, bool next) {
        Pair<Int, Int> p = templateParitions.get(index);
        if (next) {
            p.second = next;
        }
        else {
            p.first = next;
        }
        templatePartitons.set(index, p);
    }

    public List<String> getImportedVideos() {
        return importedVideos;
    }

    public void createImportedVideos() {
        importedVideos = new ArrayList<String>();
    }

    public void addImportedVideos(String video) {
        importedVideos.add(video);
    }

    public void deleteImportedVideos(String video) {
        importedVideos.remove(video);
    }

    public List<String> getVideoOrder() {
        return videoOrder;
    }

    public void createVideoOrder() {
        videoOrder = new ArrayList<String>();
    }

    public void addOrder(String order) {
        videoOrder.add(order);
    }

    public void deleteOrder(Int index) {
        videoOrder.remove(index);
    }

    public void addOrderAtPos(Int index, String order) {
        videoOrder.add(index, order);
    }

    public List<String> getAnimations() {
        return slideAnimations;
    }

    public void createAnimations() {
        slideAnimations = new ArrayList<String>();
    }

    public void addAnimation(String animation) {
        slideAnimations.add(animation);
    }

    public void deleteAnimation(Int index) {
        slideAnimations.remove(index);
    }

    public void addAnimationAtPos(Int index, String animation) {
        slideAnimations.add(index, animation);
    }
}
