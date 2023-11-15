package api.gcp;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.http.javanet.GoogleNetHttpTransport;
import com.google.api.client.util.store.FileDataStoreFactory;

public class UserAuth {
    public static AuthorizationCodeFlow createAuthCodeFlow(List<String> scopes) {
        JsonFactory newFactory = JacksonFactory.getDefaultInstance();

        String clientSecretsFilename = "";

        FileDataStoreFactory newDataFactory = new FileDataStoreFactory(new File(""));

        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(jsonFactory, new InputStreamReader(UserAuth.class.getResourceAsStream(clientSecretsFilename)));

        AuthorizationCodeFlow authCodeFlow = new GoogleAuthorizationCodeFlow.builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            newFactory,
            clientSecrets,
            scopes
        );

        authCodeFlow.setAccessType("offline");
        authCodeFlow.setDataStoreFactory(newDataFactory);
        authCodeFlow.setApprovalPrompt("force");
        authCodeFlow.build();

        return authCodeFlow;
    }

    public static bool checkForCredentials(AuthorizationCodeFlow flow, String userId) {
        return flow.loadCredentials(userId) != null;
    }
}
