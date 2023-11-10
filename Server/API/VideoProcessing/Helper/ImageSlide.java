package api.videoProcessing;

import java.util.Collections;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.io.FileOutputStream;

import com.google.api.services.slides.v1.model.Page;
import com.google.api.services.slides.v1.model.Thumbnail;


public class ImageSlide {
    public static String convertSlideToImage(Page currSlide, String presentationId) {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
        .createScoped(Collections.singleton(SlidesScopes.PRESENTATIONS));
        HttpRequestInitializer reqInitializer = new HttpCredentialsAdapter(credentials);

        Slides slidesService = new Slides.Builder(new NetHttpTransport(), 
            GsonFactory.getDefaultInstance(),
            reqInitializer)
            .setApplicationName("VideoProcessing")
            .build();

        GetThumbnailRequest thumbnailReq = new GetThumbnailRequest()
            .setObjectId(slide.getObjectId())
            .setMineType("image/png");
        
        String filename = System.getProperty("user.home") + "/Downloads/imageSlide" + slide.getObjectId() + ".png";
        
        try {
            Thumbnail thumbnail = slidesService.presentations().pages().getThumbail(presentationId, thumbnailReq).execute();

            String imageUrl = thumbnail.getContentUrl();

            URL imgUrl = new URL(imageUrl);

            InputStream inStream = imgUrl.openStream();

            OutputStream outStream = new FileOutputStream(filename);

            byte[] buffer = new byte[2049];

            int length = 0;

            while ((length = inStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, length);
            }

            inStream.close();
            outStream.close();

        } catch (Exception e) {
            throw new Exception("Couldn't convert");
        }
        return filename;
    }
}
