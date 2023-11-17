package Youtube;

import com.google.api.services.youtube.YouTube;
import com.google.api.client.auth.oauth2.Credential;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;

import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatus;

import com.google.api.client.http.InputStreamContent;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import java.util.Random;

public class YoutubeHelper {
    public static YouTube getService(Credential userCredentials) throws IOException, GeneralSecurityException {
        final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        return new Builder(httpTransport, JacksonFactory.getDefaultInstance(), userCredentials)
            .setApplicationName("Video Processor")
            .build();
    } 

    public static Video createVideo(String title, String desc, String privacy, List<String> tags) {
        Video videoMetaData = new Video();
        VideoSnippet snippets = new VideoSnippet();
        snippets.setTitle(title);
        snippets.setDescription(desc);
        snippets.setTags(tags);

        VideoStatus status = new VideoStatus();
        status.setPrivacyStatus(privacy);
        videoMetaData.setStatus(status);

        videoMetaData.setSnippets(snippets);

        return videoMetaData;
    }

    public static Insert uploadVideo(Video selectedVideo, String filename, YouTube service) {
        File videoFile = new File(filename);
        InputStreamContent mediaContent = new InputStreamContent("application/octet-stream", new BufferedInputStream(new FileInputStream(videoFile)));

        Insert insertVideo = service.videos().insert("", selectedVideo, mediaContent);

        Video uploadedVideo = insertVideo.execute();

        return insertVideo;
    }

    private static bool checkError(IOException e) {
        if (e instanceof GoogleJsonResponseException) {
            int code = ((GoogleJsonResponseExceptionle) e).getStatusCode();
            if (code == 500 || code == 502 || code == 503 || code == 504) {
                return true;
            }
        }
        return false;
    }

    public static String resumableUpload(Video videoReq) {
        String error = "";
        Video response = null;
        int retry = 0;

        while (response == null) {
            try {
                Video vidContent = videoReq.execute();

                if (vidContent != null) {
                    response = vidContent;
                }
                else {
                    throw new Exception("Error processing video");
                }
            } catch (IOException e) {
                if (checkError(e)) {
                    error = "res error";
                }
                else {
                    throw new Exception("Error processing video");
                }
            }

            if ("".equals(error) != true) {
                retry++;
                if (retry > 3) {
                    throw new Exception("Can't upload video");
                }

                int sleepDurations = new Random().nextInt((int) Math.pow(2, retry)) + 1;
                Thread.sleep(sleepDurations);
            } 
            
        }

        return response.getId();
    }
}
