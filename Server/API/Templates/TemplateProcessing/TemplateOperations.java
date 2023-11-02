package api.templates;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.slides.v1.Slides;
import com.google.api.services.slides.v1.SlidesScopes;
import com.google.api.services.slides.v1.model.Presentation;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import java.io.IOException;
import java.util.Collections;


public class TemplateOperations {
    public static String createTemplate(string templateName) {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
        .createScoped(Collections.singleton(SlidesScopes.PRESENTATIONS));
        HttpRequestInitializer reqInitializer = new HttpCredentialsAdapter(credentials);

        Slides slidesService = new Slides.Builder(new NetHttpTransport(), 
            GsonFactory.getDefaultInstance(),
            reqInitializer)
            .setApplicationName("VideoProcessing")
            .build();
        
        Presentation presentation = new Presentation()
            .setTitle(templateName);
        presentation = slidesService.presentations().create(presentation).setFields("presentationId").execute();

        return presentation.presentationId();
    }
}
