package api.templates;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.slides.v1.Slides;
import com.google.api.services.slides.v1.SlidesScopes;
import com.google.api.services.slides.v1.model.Presentation;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.services.slides.v1.model.BatchUpdatePresentationRequest;
import com.google.api.services.slides.v1.model.BatchUpdatePresentationResponse;
import com.google.api.services.slides.v1.model.CreateSlideRequest;
import com.google.api.services.slides.v1.model.LayoutReference;
import com.google.api.services.slides.v1.model.Request;

import java.io.IOException;
import java.sql.BatchUpdateException;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;


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

    public static BatchUpdatePresentationResponse createSlide(String presentationId) {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
        .createScoped(Collections.singleton(SlidesScopes.PRESENTATIONS));
        HttpRequestInitializer reqInitializer = new HttpCredentialsAdapter(credentials);

        Slides service = new Slides.Builder(new NetHttpTransport(),
            GsonFactory.getDefaultInstance(), 
            reqInitializer)
            .setApplicationName("VideoProcessing")
            .build();

        List<Request> req = new ArrayList<>();
        BatchUpdatePresentationResponse res = null;
        try {
            Request newSlide = new Request()
                .setCreateSlide(new CreateSlideRequest()
                    .setSlideLayoutReference(new LayoutReference())
                        .setPredefinedLayout("TITLE_AND_TWO_COLUMNS"));
            req.add(newSlide);


            BatchUpdatePresentationRequest body = new BatchUpdatePresentationRequest().setRequests(req);
            res = service.presentations().batchUpdate(presentationId, body).execute();
        }
        catch (GoogleJsonResponseException e) {
            GoogleJsonError error = e.getDetails();
            if (error.getCode() == 400) {
                throw new Exception("Id is not unique");
            }
            else if (error.getCode() == 404) {
                throw new Exception("Couldn't find presentation");
            }
            else {
                throw e;
            }
        }
        return res;
    }
}
