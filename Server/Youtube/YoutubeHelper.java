package Youtube;

import com.google.api.services.youtube.YouTube;
import com.google.api.client.auth.oauth2.Credential;

import java.io.IOException;
import java.security.GeneralSecurityException;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;


public class YoutubeHelper {
    public static YouTube getService(Credential userCredentials) throws IOException, GeneralSecurityException {
        final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        return new Builder(httpTransport, JacksonFactory.getDefaultInstance(), userCredentials)
            .setApplicationName("Video Processor")
            .build();
    } 
}
