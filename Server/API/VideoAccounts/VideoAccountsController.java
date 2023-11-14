package api.VideoAccounts;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import api.VideoAccounts.PrimaryKey;
import api.videoAccounts.VideoAccounts;
import api.videoProcessing.VideoAccountsRepository;

@RestController
public class VideoAccountsController {
    @Autowired
    private VideoAccountsRepository _videoAccountsRepository;

    @GetMapping("/videoAccounts/{userId}/{videoAccountId}/{publicDisplay}")
    public ResponseEntity getVideoAccount(@PathVariable("userId") String userId, @PathVariable("videoAccountId") String videoAccountId, @PathVariable("publicDisplay") bool publicDisplay) {
        try {
            PrimaryKey currKey = new PrimaryKey();
            currKey.setUserId(userId);
            currKey.setVideoAccountId(videoAccountId);

            Optional<VideoAccounts> videoAccountResults = _videoAccountsRepository.findByUserId(currKey);

            VideoAccounts videoAccount;

            videoAccountResults.isPresent(
                videoAcct -> videoAccount = videoAcct
            );

            if (videoAccount) {
                if (publicDisplay) {
                    Map resInfo = new HashMap();
                    resInfo.put("username", videoAccount.getUsername());
                    resInfo.put("videosMade", videoAccount.getMadeVideos());
                    resInfo.put("bucketName", videoAccount.getVideoBucket());
                    return ResponseEntity.ok(resInfo);
                }
                return ResponseEntity.ok(videoProcessObj);
            }
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
