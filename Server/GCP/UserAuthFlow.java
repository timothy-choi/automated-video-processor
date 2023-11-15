package api.gcp;


import java.util.*;
import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.TokenResponse;

public class UserAuthFlow {
    public static String getAuthUrl(AuthorizationCodeFlow flow) {
        AuthorizationCodeRequestUrl authUrl = flow.newAuthorizationUrl();
        return authUrl.setRedirectUri("").build(); 
    }

    public static TokenResponse sendTokenRequest(String code, AuthorizationCodeFlow flow, List<String> scopes) {
        TokenResponse res = flow.newTokenRequest(code).setScopes(scopes).setRedirectUri("").build();
        return res;
    }
}
