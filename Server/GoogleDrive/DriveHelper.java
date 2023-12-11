package GoogleDrive;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener;

import api.gcp;


public class DriveHelper {
    public static Drive getService(Credential userCredentials) throws IOException, GeneralSecurityException {
        final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        return new Builder(httpTransport, JacksonFactory.getDefaultInstance(), userCredentials)
            .setApplicationName("Video Processor")
            .build();
    }

    public static bool uploadVideo(Drive service, string videoFilename) throws IOException {
        File videoMetadata = new File();
        videoMetadata.setName(videoFilename);
        java.io.File filePath = new java.io.File(videoFilename);
        FileContent fileData = new FileContent("video/mp4", filePath);

        try {
            Drive.Files create = service.files().create(videoMetadata, fileData).setFields("id").
            setMediaHttpUploader(new ResumableMediaHttpUploader(
                            service.getHttpRequestInitializer(),
                            service.getTransport(),
                            service.getRequestFactory()
                    )).setProgressListener(new CustomProgressListener());
            
            create.create();

            return true;
        }
        catch (GoogleJsonResponseException e) {
            return false;
        }
    }

    static class CustomProgressListener implements MediaHttpUploaderProgressListener {
        @Override
        public void progressChanged(MediaHttpUploader uploader) throws IOException {
                switch (uploader.getUploadState()) {
                    case INITIATION_STARTED:
                        System.out.println("Initiation Started");
                        break;
                    case INITIATION_COMPLETE:
                        System.out.println("Initiation Completed");
                        break;
                    case MEDIA_IN_PROGRESS:
                        System.out.println("Upload in progress: " + uploader.getProgress());
                        break;
                    case MEDIA_COMPLETE:
                        System.out.println("Upload Completed");
                        break;
                    default:
                        break;
                }
        }
    }
}
