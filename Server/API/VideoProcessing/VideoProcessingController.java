package api.videoProcessing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.reactive.function.client.WebClient;

import api.videoProcessing.PrimaryKey;
import api.videoProcessing.VideoProcessingRepository;
import api.videoProcessing.VideoProcessing;
import api.WebClientConfig;

import java.util.*;

@RestController
public class VideoProcessingController {
    @Autowired
    private VideoProcessingRepository _videoProcessingRepository;

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
}
