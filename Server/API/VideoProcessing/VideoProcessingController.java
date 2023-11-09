package api.videoProcessing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.google.api.services.slides.v1.model.Presentation;
import com.google.api.services.slides.v1.model.Page;

import org.springframework.web.reactive.function.client.WebClient;

import api.WebClientConfig;
import api.videoProcessing.VideoProcessing;
import javafx.util.Pair;

import AWS;
import AWS.AWSHelper;

import java.util.*;

@RestController
public class VideoProcessingController {
    @Autowired
    private VideoProcessingRepository _videoProcessingRepository;

    private AWSHelper client;

    @GetMapping("/videoProcessing/{userId}/{videoProcessingId}/{publicDisplay}")
    public ResponseEntity getVideoProcessingInstance(@PathVariable("userId") String userId, @PathVariable("videoProcessingId") String videoProcessingId, @PathVariable("publicDisplay") bool publicDisplay) {
        PrimaryKey newKey = new PrimaryKey();
        newKey.setUserId(userId);
        newKey.setVideoProcessingId(videoProcessingId);
        Optional<VideoProcessing> videoProcess = _videoProcessingRepository.findByUserId(newKey);

        VideoProcessing videoProcessObj;

        videoProcess.isPresent(
            videoPro -> videoProcessObj = videoPro
        );

        if (videoProcessObj) {
            if (publicDisplay) {
                Map resInfo = new HashMap();
                resInfo.put("name", videoProcessObj.getName());
                resInfo.put("templateId", videoProcessObj.getTemplateId());
                resInfo.put("creator", videoProcessObj.getCreator());
                resInfo.put("slideImages", videoProcessObj.getAllImageSlides());
                resInfo.put("templatePartitions", videoProcessObj.getPartitions());
                resInfo.put("importedVideos", videoProcessObj.getImportedVideos());
                resInfo.put("videoOrder", videoProcessObj.getVideoOrder());
                resInfo.put("slideAnimations", videoProcessObj.getAnimations());
                return ResponseEntity.ok(resInfo);
            }
            return ResponseEntity.ok(videoProcessObj);
        }

        return ResponseEntity.notFound().build();
    }

    @PostMapping("/videoProcessing/") 
    public ResponseEntity createVideoProcessingInstance(@RequestBody Map<String, String> reqInfo) {
        try {
           VideoProcessing videoObj = new VideoProcessing()
                .setUserId()
                .setVideoProcessingId()
                .setName(reqInfo.get("templateName"))
                .setTemplateId(reqInfo.get("templateId"))
                .setCreator(reqInfo.get("creator"))
                .createImageSlides()
                .createPartitions()
                .createImportedVideos()
                .createAnimations()
                .createVideoOrder();
            Pair<String, String> keyInfo = new Pair<String, String>();
            keyInfo.set("userId", videoObj.getUserId());
            keyInfo.set("videoProcessingId", videoObj.getVideoProcessingId());
           return ResponseEntity.ok(keyInfo);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/videoProcessing/partitions")
    public ResponseEntity addSlidePartitions(@RequestBody Map<String, String> reqInfo) {
        try {
            Template corrTemplate = WebClientConfig.webClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/templates/{userId}/{templateId}/{publicDisplay}")
                .build(reqInfo.get("userId"), reqId.get("templateId"), false))
            .retrieve()
            .bodyToMono(Template.class);

            List<Int> partitions = corrTemplate.getPartitions();

            VideoProcessing videoProcessObj = WebClientConfig.WebClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/videoProcessing/{userId}/{videoProcessingId}/{publicDisplay}")
                .build(reqInfo.get("userId"), reqInfo.get("videoProcessingId"), false))
            .retrieve()
            .bodyToMono(VideoProcessing.class);


            for (int i = 0; i < partitions.size(); ++i) {
                int prevIndex = -1;
                if (i == 0) {
                    prevIndex = 0;
                }
                else {
                    prevIndex = partitions.get(i-1)+1;
                }
                Pair<Int, Int> currPartition = new Pair<Int, Int>(prevIndex, partitions.get(i));
                videoProcessObj.addPartition(currPartition);
            }

            if (partitions.get(partitions.size()-1) < corrTemplate.getSlides().size()-1) {
                Pair<Int, Int> lastPartition = new Pair<Int, Int>(partitions.get(partitions.size()-1) + 1, corrTemplate.getSlides().size()-1);
                videoProcessObj.addPartition(lastPartition); 
            }

            _videoProcessingRepository.save(videoProcessObj);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/videoProcessing/images")
    public ResponseEntity convertSlidesToImages(@RequestBody Map<String, String> reqInfo) {
        try {
            Presentation pres = WebClientConfig.WebClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/template/presentation/{presentationId}")
                .build(reqInfo.get("templateId")))
            .retrieve();

            VideoProcessing videoProcessObj = WebClientConfig.WebClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/videoProcessing/{userId}/{videoProcessingId}/{publicDisplay}")
                .build(reqInfo.get("userId"), reqInfo.get("videoProcessingId"), false))
            .retrieve()
            .bodyToMono(VideoProcessing.class);

            List<Page> slides = pres.getSlides();

            for (Page slide : slides) {
                String imageName = ImageSlide.convertSlideToImage(slide, reqInfo.get("templateId"));

                videoProcessObj.addImageSlide(imageName);
            }

            _videoProcessingRepository.save(videoProcessObj);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    } 

    @DeleteMapping("/videoProcessing/partitions/{userId}/{videoProcessingId}/{PartitionNum}")
    public ResponseEntity deletePartition(@PathVariable("userId") String userId, @PathVariable("videoProcessingId") String videoProcessingId, @PathVariable("partitionNum") Int partitionNum) {
        try {
            VideoProcessing videoProcessObj = WebClientConfig.WebClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/videoProcessing/{userId}/{videoProcessingId}/{publicDisplay}")
                .build(reqInfo.get("userId"), reqInfo.get("videoProcessingId"), false))
            .retrieve()
            .bodyToMono(VideoProcessing.class);

            Pair<Int, Int> currPair = videoProcessObj.getPartitions().get(partitionNum);

            videoProcessObj.deletePartition(currPair);

            if (partitionNum == 0) {
                videoProcessObj.editPartition(partitionNum+1, currPair.first, true);
            }
            else if (partitionNum == videoProcessObj.getPartitions().size() - 1) {
                videoProcessObj.editPartition(partition-1, currPair.second,false);
            }
            else {
                videoProcessObj.editPartition(partitionNum+1, currPair.first, true);
            }

            _videoProcessingRepository.save(videoProcessObj);

            return ResponseEntity.ok();
        }
        catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/videoProcessing/importedVideo")
    public ResponseEntity uploadImportedVideo(@RequestParam("video") MultipartFile video, @RequestBody Map<String, String> reqInfo) {
        String objKey = reqInfo.get("owner") + "/imported/" + video.getOriginalFilename();
        try {
            client.addObjectIntoBucket(reqInfo.get("bucketName"), objKey, video);
        }
        catch (AmazonS3Exception e) {
            return ResponseEntity.notFound().build();
        }

        try {
            VideoProcessing videoProcessObj = WebClientConfig.WebClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/videoProcessing/{userId}/{videoProcessingId}/{publicDisplay}")
                .build(reqInfo.get("userId"), reqInfo.get("videoProcessingId"), false))
            .retrieve()
            .bodyToMono(VideoProcessing.class);

            videoProcessObj.addImportedVideos(objKey);

            _videoProcessingRepository.save(videoProcessObj);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok();
    }

    @PostMapping("/videoProcessing/replaceVideo")
    public ResponseEntity replaceImportedVideo(@RequestParam("newVideo") MultipartFile newVideo, @RequestBody Map<String, String> reqInfo) {
        try {
            client.replaceObjectInBucket(reqInfo.get("bucketName"), reqInfo.get("objKey"), newVideo);
            return ResponseEntity.ok();
        }
        catch (AmazonS3Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/videoProcessing/order")
    public ResponseEntity addNewVideoOrder(@RequestBody Map<String, String> reqInfo) {
        try {
            VideoProcessing videoProcessObj = WebClientConfig.WebClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/videoProcessing/{userId}/{videoProcessingId}/{publicDisplay}")
                .build(reqInfo.get("userId"), reqInfo.get("videoProcessingId"), false))
            .retrieve()
            .bodyToMono(VideoProcessing.class);

            if (Boolean.parseBoolean(reqInfo.get("positioned"))) {
                videoProcessObj.addOrderAtPos(Integer.parseInt(req.get("index")), reqInfo.get("filename"));
            }
            else {
                videoProcessObj.addOrder(reqInfo.get("filename"));
            }

            _videoProcessingRepository.save(videoProcessObj);

            return ResponseEntity.ok();
        }
        catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/videoProcessing/order/reposition")
    public ResponseEntity repositionVideoOrder(@RequestBody Map reqInfo) {
        try {
            VideoProcessing videoProcessObj = WebClientConfig.WebClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/videoProcessing/{userId}/{videoProcessingId}/{publicDisplay}")
                .build(reqInfo.get("userId"), reqInfo.get("videoProcessingId"), false))
            .retrieve()
            .bodyToMono(VideoProcessing.class);

            List<Int> changes = reqInfo.get("OrderChanges");

            for (Int change : changes) {
                String file = videoProcessObj.getVideoOrder().get(change);
                videoProcessObj.deleteOrder(file);
                videoProcessObj.addOrderAtPos(change, file);
            }

            _videoProcessingRepository.save(videoProcessObj);

            return ResponseEntity.ok();
        }
        catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/videoProcessing/animations")
    public ResponseEntity addAnimation(@RequestBody Map<String, String> reqInfo) {
        try {
            VideoProcessing videoProcessObj = WebClientConfig.WebClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/videoProcessing/{userId}/{videoProcessingId}/{publicDisplay}")
                .build(reqInfo.get("userId"), reqInfo.get("videoProcessingId"), false))
            .retrieve()
            .bodyToMono(VideoProcessing.class);

            if (Boolean.parseBoolean(reqInfo.get("positioned"))) {
                videoProcessObj.addAnimation(Integer.parseInt(reqInfo.get("index")), reqInfo.get("slideId") + " " + reqInfo.get("animation"));
            }
            else {
                videoProcessObj.addAnimation(reqInfo.get("slideId") + " " + reqInfo.get("animation"));
            }

            _videoProcessingRepository.save(videoProcessObj);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/videoProcessing/animations/{userId}/{videoProcessingId}/{slideId}")
    public ResponseEntity deleteAnimation(@PathVariable("userId") String userId, @PathVariable("videoProcessingId") String videoProcessingId, @PathVariable("slideId") String slideId) {
        try {
            VideoProcessing videoProcessObj = WebClientConfig.WebClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/videoProcessing/{userId}/{videoProcessingId}/{publicDisplay}")
                .build(userId, videoProcessingId, false))
            .retrieve()
            .bodyToMono(VideoProcessing.class);

            List<String> animations = videoProcessObj.getAnimations();
            for (int i = 0; i < animations.size(); ++i) {
                if (animations.get(i).contains(slideId)) {
                    animations.delete(i);
                    break;
                }
            }

            _videoProcessingRepository.save(videoProcessObj);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
