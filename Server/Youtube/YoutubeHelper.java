package Youtube;

import com.google.api.services.youtube.YouTube;
import com.google.api.client.auth.oauth2.Credential;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;

import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatus;


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
}
