package com.kinde.session;

import com.google.inject.Inject;
import com.kinde.authorization.AuthorizationUrl;
import com.kinde.client.OidcMetaData;
import com.kinde.config.KindeConfig;
import com.kinde.guice.KindeAnnotations;
import com.kinde.token.AccessToken;
import com.kinde.token.IDToken;
import com.kinde.token.KindeTokens;
import com.kinde.token.RefreshToken;
import com.kinde.user.UserInfo;
import com.nimbusds.oauth2.sdk.*;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPRequest;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.openid.connect.sdk.UserInfoRequest;
import com.nimbusds.openid.connect.sdk.UserInfoResponse;
import com.kinde.token.TokenManager;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.net.URI;

@Slf4j
public class KindeClientCodeSessionImpl extends KindeClientSessionImpl {

    private final String code;
    private final AuthorizationUrl authorizationUrl;
    private AccessToken accessToken;

    @Inject
    public KindeClientCodeSessionImpl(
            KindeConfig kindeConfig,
            OidcMetaData oidcMetaData,
            @KindeAnnotations.KindeCode String code,
            @KindeAnnotations.AuthorizationUrl @Nullable AuthorizationUrl authorizationUrl) {
        super(kindeConfig, oidcMetaData);
        this.code = code;
        this.authorizationUrl = authorizationUrl;
    }

    @Override
    @SneakyThrows
    public KindeTokens retrieveTokens() {
        AuthorizationCode code = new AuthorizationCode(this.code);
        URI callback = new URI(this.kindeConfig.redirectUri());
        AuthorizationGrant codeGrant = this.authorizationUrl == null ? new AuthorizationCodeGrant(code, callback) :
                new AuthorizationCodeGrant(code, callback, this.authorizationUrl.getCodeVerifier());

        ClientID clientID = new ClientID(this.kindeConfig.clientId());
        Secret clientSecret = new Secret(this.kindeConfig.clientSecret());
        ClientAuthentication clientAuth = new ClientSecretBasic(clientID, clientSecret);
        URI tokenEndpoint = this.oidcMetaData.getOpMetadata().getTokenEndpointURI();

        TokenRequest request = new TokenRequest(tokenEndpoint, clientAuth, codeGrant);
        HTTPRequest httpRequest = request.toHTTPRequest();
        httpRequest.setHeader("Kinde-SDK", "Java/2.0.1");

        TokenResponse response = TokenResponse.parse(httpRequest.send());

        if (!response.indicatesSuccess()) {
            throw new Exception(response.toErrorResponse().toString());
        }
        AccessTokenResponse successResponse = response.toSuccessResponse();

        String idTokenStr = (String) successResponse.getCustomParameters().get("id_token");

        IDToken idToken = null;
        if (idTokenStr != null) {
            idToken = IDToken.init(idTokenStr, true);
        }

        this.accessToken = com.kinde.token.AccessToken.init(successResponse.getTokens().getAccessToken().getValue(), true);

        RefreshToken refreshToken = null;
        if (successResponse.getTokens().getRefreshToken() != null) {
            refreshToken = com.kinde.token.RefreshToken.init(successResponse.getTokens().getRefreshToken().getValue(), true);
        }

        return new KindeTokens(this.kindeConfig.scopes(), idToken, this.accessToken, refreshToken);
    }

    @Override
    @SneakyThrows
    public UserInfo retrieveUserInfo() {
        if (this.accessToken == null) {
            retrieveTokens();
        }
        BearerAccessToken token = new BearerAccessToken(this.accessToken.token());

        HTTPResponse httpResponse = new UserInfoRequest(this.oidcMetaData.getOpMetadata().getUserInfoEndpointURI(), token)
                .toHTTPRequest()
                .send();

        UserInfoResponse userInfoResponse = UserInfoResponse.parse(httpResponse);

        if (!userInfoResponse.indicatesSuccess()) {
            throw new Exception(userInfoResponse.toErrorResponse().toString());
        }

        return new UserInfo(userInfoResponse.toSuccessResponse().getUserInfo());
    }
    
    @Override
    public boolean isAuthenticated() throws Exception {
        if (this.accessToken == null) {
            retrieveTokens();
        }

        if (this.accessToken == null || !this.accessToken.valid()) {
            log.warn("Access token is null or invalid");
            return false;
        }
        
        KindeTokens tokens = retrieveTokens();
        TokenManager tokenManager = new TokenManager(tokens, 5);
        
        if (tokenManager.needsRefresh() && tokenManager.canRefresh()) {
            log.info("Auto-refreshing tokens due to imminent expiry");
            KindeTokens newTokens = tokenManager.refresh();
            this.accessToken = newTokens.getAccessToken();
            return true;
        } else if (tokenManager.needsRefresh() && !tokenManager.canRefresh()) {
            log.warn("Token expired and no refresh token available");
            return false;
        }
        
        return true;
    }
}
