package api.gcp;


import java.util.*;
import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.AuthorizationCodeFlow;

public class UserAuthFlow {
    public static String getAuthUrl(AuthorizationCodeFlow flow) {
        AuthorizationCodeRequestUrl authUrl = flow.newAuthorizationUrl();
        return authUrl.setRedirectUrl("").build(); 
    }
}
