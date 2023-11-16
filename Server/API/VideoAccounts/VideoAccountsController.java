package api.VideoAccounts;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import api.VideoAccounts.PrimaryKey;
import api.videoAccounts.VideoAccounts;
import api.videoProcessing.VideoAccountsRepository;
import api.WebClientConfig;

import Server.gcp;
import com.google.api.client.googleapis.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.TokenResponse;

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

            Optional<VideoAccounts> videoAccountResults = _videoAccountsRepository.findById(currKey);

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

    @PostMapping("/videoAccounts")
    public ResponseEntity createVideoAccount(@RequestBody Map reqInfo) {
        try {
            VideoAccounts acct = new VideoAccounts();
            acct.setVideoAccountId();
            acct.setUserId();
            acct.setUsername(reqInfo.get("username"));
            acct.createMadeVideos();
            acct.setVideoBucket(reqInfo.get("bucketname"));

            _videoAccountsRepository.save(acct);
            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/videoAccounts/videos")
    public ResponseEntity addVideo(@RequestBody Map reqInfo) {
        try {
            VideoAccounts videoAcct = WebClientConfig.WebClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/videoAccounts/{userId}/{videoAccountId}/{publicDisplay}")
                .build(reqInfo.get("userId"), reqInfo.get("videoAccountId"), false))
            .retrieve()
            .bodyToMono(VideoAccounts.class);

            videoAcct.addCreatedVideo(reqInfo.get("video"));

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/videoAccounts/{userId}/{videoAccountId}/{video}")
    public ResponseEntity deleteVideo(@PathVariable("userId") String userId, @PathVariable("videoAccountId") String videoAccountId, @PathVariable("video") String video) {
        try {
            VideoAccounts videoAcct = WebClientConfig.WebClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/videoAccounts/{userId}/{videoAccountId}/{publicDisplay}")
                .build(userId, videoAccountId, false))
            .retrieve()
            .bodyToMono(VideoAccounts.class);

            videoAcct.delete(video);
            _videoAccountsRepository.save(videoAcct);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/videoAccounts/{userId}/{videoAccountId}")
    public ResponseEntity deleteVideoAccount(@PathVariable("userId") String userId, @PathVariable("videoAccountId") String videoAccountId) {
        try {
            VideoAccounts videoAcct = WebClientConfig.WebClient().get()
            .uri(uriBuilder -> uriBuilder
                .path("/videoAccounts/{userId}/{videoAccountId}/{publicDisplay}")
                .build(userId, videoAccountId, false))
            .retrieve()
            .bodyToMono(VideoAccounts.class);

            _videoAccountsRepository.delete(videoAcct);

            return ResponseEntity.ok();
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/videoAccounts/auth") 
    public ResponseEntity authorizeUserAccount(@RequestBody Map reqInfo) {
        try {
            AuthorizationCodeFlow flow = UserAuth.createAuthCodeFlow(reqInfo.get("scopes"));

            if (UserAuth.checkForCredentials(flow, reqInfo.get("id")) == null) {
                return ResponseEntity.ok(UserAuth.getCredential(flow, reqInfo.get("id")));
            }

            String url = UserAuthFlow.getAuthUrl(flow);
            return ResponseEntity.ok(url);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/videoAccounts/authflow")
    public ResponseEntity initializeUserAuth(@RequestBody Map reqInfo) {
        try {
            AuthorizationCodeFlow flow = UserAuth.createAuthCodeFlow(reqInfo.get("scopes"));

            TokenResponse token = UserAuthFlow.sendTokenRequest(reqInfo.get("code"), flow, reqInfo.get("scopes"));

            Credential newCreds = UserAuthFlow.createCredential(token, flow, reqInfo.get("id"));

            return ResponsEntity.ok(newCreds);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
