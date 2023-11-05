package api.templates;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.slides.v1.Slides;
import com.google.api.services.slides.v1.SlidesScopes;
import com.google.api.services.slides.v1.model.Presentation;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;

import apple.laf.JRSUIConstants.Size;

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

import org.omg.PortableInterceptor.RequestInfo;

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

    public static BatchUpdatePresentationResponse createSlide(String presentationId, String slideId, bool color, double red, double blue, double green) {
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
                    .setObjectId(slideId)
                    .setSlideLayoutReference(new LayoutReference())
                        .setPredefinedLayout("TITLE_AND_TWO_COLUMNS"));
            req.add(newSlide);

            if (color) {
                req.add(new RequestInfo() 
                    .updatePageProperties(new UpdatePagePropertiesRequest()
                        .setObjectId(slideId)
                        .setPageProperties(new PageProperties()
                            .setPageBackgrounFill(new PageBackgroundFill()
                                .setSolidFill(new SolidFill()
                                    .setColor(new RgbColor() 
                                        .setRed(red)
                                        .setBlue(blue)
                                        .setGreen(green)))))));
            }
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

    public static BatchUpdatePresentationResponse addText(String presentationId, String textBoxId, String text, String prevText) throws IOException {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
        .createScoped(Collections.singleton(SlidesScopes.PRESENTATIONS));
        HttpRequestInitializer reqInitializer = new HttpCredentialsAdapter(credentials);

        Slides service = new Slides.Builder(new NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            reqInitializer)
            .setApplicationName("VideoProcessing")
            .build();
        
        List<Request> req = new ArrayList<>();

        req.add(new Request()
            .setInsertText(new InsertTextRequest()
                .setObjectId(textBodyId)
                .setText(text)
                .setinsertionIndex(prevText.size())));

        BatchUpdatePresentationResponse res = null;
        try {
            BatchUpdatePresentationRequest body = new BatchUpdatePresentationRequest().setRequests(requests);
            res = service.presentations().batchUpdate(presentationId, body).execute();

        } catch (GoogleJsonResponseException e) {
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

    public static BatchUpdatePresentationResponse createShape(String presentationId, String shapeId, String shape, String slideID, bool transparent, double magnitude, double red, double green, double blue) {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
        .createScoped(Collections.singleton(SlidesScopes.PRESENTATIONS));
        HttpRequestInitializer reqInitializer = new HttpCredentialsAdapter(credentials);

        Slides service = new Slides.Builder(new NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            reqInitializer)
            .setApplicationName("VideoProcessing")
            .build();
        
        Dimension pageSize = new Dimension().setMagnitude(magnitude).setUnit("PT");
        
        List<Request> req = new ArrayList<>();

        if (border) {
            req.add(new RequestInfo() 
            .setCreateShape(new CreateShapeRequest()
                .setObjectId(shapeId)
                .setShapeType(shape)
                .setElementProperties(new PageElementProperties()
                    .setPageObjectId(slideID)
                    .setSize(new Size()
                        .setHeight(pageSize)
                        .setWeight(pageSize)
                        .setUnit("PT")
                    ))));

            req.add(new RequestInfo() 
                .updateShapeProperties(new UpdateShapePropertiesRequest()
                    .setObjectId(shapeId)
                    .setShapeProperties(new ShapeProperties()
                        .setShapeBackgroundFill(new ShapeBackgroundFill()
                            .setPropertyState(PropertyState.RENDERED))
                            .setSolidFill(new SolidFill()
                                .setColor(new OpaqueColor()
                                    .setRgbColor(new RgbColor()
                                        .setRed(red)
                                        .setBlue(blue)
                                        .setGreen(green))
                                .setAlpha(1.0)))

                    )));
            } else {
                req.add(new RequestInfo() 
                .setCreateShape(new CreateShapeRequest()
                    .setObjectId(shapeId)
                    .setShapeType(shape)
                    .setElementProperties(new PageElementProperties()
                        .setPageObjectId(slideID)
                        .setSize(new Size()
                            .setHeight(pageSize)
                            .setWeight(pageSize)
                            .setUnit("PT")
                        ))));
            }
        
        BatchUpdatePresentationResponse res = null;

        try {
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

        public static BatchUpdatePresentationResponse replaceText(String presentationId, String shapeId, String newText) throws IOException {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
            .createScoped(Collections.singleton(SlidesScopes.PRESENTATIONS));
            HttpRequestInitializer reqInitializer = new HttpCredentialsAdapter(credentials);

            Slides service = new Slides.Builder(new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                reqInitializer)
                    .setApplicationName("VideoProcessing")
                    .build();
            
            List<Request> req = new ArrayList<>();

            req.add(new Request()
                .setDeleteText(new DeleteTextRequest()
                    .setObjectId(shapeId)
                    .setTextRange(new Range()
                        .setType("ALL"))));
            req.add(new Request()
                .setInsertText(new InsertTextRequest()
                    .setObjectId(shapeId)
                    .setText(newText)));
            
            BatchUpdatePresentationResponse res = null;

            try {
                BatchUpdatePresentationRequest body = new BatchUpdatePresentationRequest().setRequests(requests);
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

        public static BatchUpdatePresentationResponse addImage(String presentationId, String slideId, String imageUrl, double magnitude) {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
            .createScoped(Collections.singleton(SlidesScopes.PRESENTATIONS));
            HttpRequestInitializer reqInitializer = new HttpCredentialsAdapter(credentials);

            Slides service = new Slides.Builder(new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                reqInitializer)
                    .setApplicationName("VideoProcessing")
                    .build();
            
            List<Request> req = new ArrayList<>();

            Dimension size = new Dimension().setMagnitude(magnitude).setUnit("EMU");

            req.add(new Request()
                .setCreateImage(new CreateImageRequest()
                    .setUrl(imageUrl)
                    .setElementProperties(new PageElementProperties()
                        .setPageObjectId(slideId)
                        .setSize(new Size()
                            .setHeight(size)
                            .setWidth(size)))));
            
            BatchUpdatePresentationResponse res = null;

            try {
                BatchUpdatePresentationRequest body = new BatchUpdatePresentationRequest().setRequests(requests);
                res = service.presentations().batchUpdate(presentationId, body).execute();

            } catch (GoogleJsonResponseException e) {
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

        public static BatchUpdatePresentationResponse moveSlide(String presentationId, String slideId, Int newPos) throws IOException {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
            .createScoped(Collections.singleton(SlidesScopes.PRESENTATIONS));
            HttpRequestInitializer reqInitializer = new HttpCredentialsAdapter(credentials);

            Slides service = new Slides.Builder(new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                reqInitializer)
                    .setApplicationName("VideoProcessing")
                    .build();

            List<Request> req = new ArrayList<Request>();

            req.add(new Request()
                .updateSlidesPosition(new UpdateSlidePositionRequest()
                    .setSlideObjectId(new List<String>(Arrays.asList(slideId))
                    .insertionIndex(newPos))));
            
            BatchUpdatePresentationResponse res = null;

            try {
                BatchUpdatePresentationRequest body = new BatchUpdatePresentationRequest().setRequests(requests);
                res = service.presentations().batchUpdate(presentationId, body).execute();

            } catch (GoogleJsonResponseException e) {
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

        public static BatchUpdatePresentationResponse deleteSlide(String presentationId, String slideId) throws IOException {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
            .createScoped(Collections.singleton(SlidesScopes.PRESENTATIONS));
            HttpRequestInitializer reqInitializer = new HttpCredentialsAdapter(credentials);

            Slides service = new Slides.Builder(new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                reqInitializer)
                    .setApplicationName("VideoProcessing")
                    .build();

            List<Request> req = new ArrayList<Request>();

            req.add(new Request()
                .deleteObject(new DeleteObjectRequest()
                    .setObjectId(slideId)));
            
            BatchUpdatePresentationResponse res = null;

            try {
                BatchUpdatePresentationRequest body = new BatchUpdatePresentationRequest().setRequests(requests);
                res = service.presentations().batchUpdate(presentationId, body).execute();

            } catch (GoogleJsonResponseException e) {
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
