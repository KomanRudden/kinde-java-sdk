package com.kinde.oauth.service;

import com.kinde.KindeClient;
import com.kinde.KindeClientBuilder;
import com.kinde.KindeTokenFactory;
import com.kinde.authorization.AuthorizationUrl;
import com.kinde.oauth.model.KindeProfile;
import com.kinde.token.KindeToken;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.AbstractOAuth2Token;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashSet;
import java.util.Optional;

import static org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction.oauth2AuthorizedClient;

/**
 * Service class to handle interactions with the Kinde OAuth2 authorization service
 * and retrieve user information.
 */
@Service
@Slf4j
public class KindeService {

    private final OAuth2AuthorizedClientService authorizedClientService;
    private final WebClient userProfileClient;

    @Value("${KINDE_DOMAIN}")
    private String kindeDomain;
    @Value("${app.base.url}")
    private String appBaseUrl;

    /**
     * Constructor to initialize the KindeService.
     *
     * @param authorizedClientService the service for managing authorized OAuth2 clients.
     * @param userProfileClient       the {@link WebClient} used to fetch the user profile.
     */
    public KindeService(OAuth2AuthorizedClientService authorizedClientService, WebClient userProfileClient) {
        this.authorizedClientService = authorizedClientService;
        this.userProfileClient = userProfileClient;
    }

    /**
     * Loads the user dashboard by fetching the OAuth2 authorized client and retrieving
     * the user profile data.
     *
     * @return a {@link KindeProfile} object containing user details.
     */
    public String loadDashboard(HttpSession session, Model model) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!authentication.isAuthenticated()) {
            return "redirect:/login";
        }

        KindeProfile kindeProfile = (KindeProfile) session.getAttribute("kindeProfile");
        if (kindeProfile == null) {
            DefaultOidcUser principal = (DefaultOidcUser) authentication.getPrincipal();
            OAuth2AuthorizedClient authorizedClient = getOAuth2AuthorizedClient(authentication);
            if (authorizedClient == null) {
                log.error("Authorized client is null");
                return "redirect:/login";
            }

            String userProfile = (String) session.getAttribute("userProfile");
            if (userProfile == null) {
                userProfile = this.userProfileClient.get().attributes(oauth2AuthorizedClient(authorizedClient)).retrieve().bodyToMono(String.class).block();
                session.setAttribute("userProfile", userProfile);
            }

            String refreshToken = Optional.ofNullable(authorizedClient.getRefreshToken()).map(AbstractOAuth2Token::getTokenValue).orElse(null);
            kindeProfile = KindeProfile.builder()
                    .accessToken(authorizedClient.getAccessToken().getTokenValue())
                    .refreshToken(refreshToken)
                    .fullName(Optional.ofNullable(principal.getUserInfo()).map(userInfo -> userInfo.getClaims().get("name")).map(Object::toString).filter(name -> !name.isEmpty()).orElse("Unknown"))
                    .idToken(principal.getIdToken().getTokenValue())
                    .roles(new HashSet<>(principal.getAuthorities()))
                    .userProfile(userProfile)
                    .build();
            session.setAttribute("kindeProfile", kindeProfile);
        }

        boolean valid = validateToken();
        if (!valid) {
            log.error("Token is not valid");
            return "redirect:/login";
        }

        model.addAttribute("kindeProfile", kindeProfile);
        return "dashboard";
    }

    /**
     * Retrieves the OAuth2 authorized client for the authenticated user.
     *
     * @param authentication the current user's {@link Authentication} object.
     * @return the {@link OAuth2AuthorizedClient} for the user.
     */
    private OAuth2AuthorizedClient getOAuth2AuthorizedClient(Authentication authentication) {
        return authorizedClientService.loadAuthorizedClient("kinde", authentication.getName());
    }

    /**
     * Validates the provided OAuth2 token using the Kinde SDK.
     *
     * @return true if the token is valid, false otherwise.
     */
    private boolean validateToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient("kinde", authentication.getName());
        if (authorizedClient == null) {
            log.error("Authorized client is null");
            return false;
        }

        OAuth2AccessToken accessToken = authorizedClient.getAccessToken();
        if (accessToken == null) {
            log.error("Access token is null");
            return false;
        }

        KindeClient kindeClient = KindeClientBuilder.builder().build();
        KindeTokenFactory kindeTokenFactory = kindeClient.tokenFactory();
        try {
            KindeToken parsedToken = kindeTokenFactory.parse(accessToken.getTokenValue());
            if (!parsedToken.valid()) {
                log.error("Parsed token is not valid");
                return false;
            }
        } catch (IllegalStateException e) {
            log.error("Exception parsing token: {}", e.getMessage());
            return false;
        }

        return true;
    }

    public String generatePortalUrl(HttpSession session) {
        KindeClient kindeClient = KindeClientBuilder.builder().build();
        KindeProfile profile = (KindeProfile) session.getAttribute("kindeProfile");
        if (profile == null || profile.getRefreshToken() == null || profile.getRefreshToken().isBlank()) {
            log.warn("Missing profile or refresh token in session");
            return "redirect:/dashboard";
        }

        KindeToken kindeToken = kindeClient.tokenFactory().parse(profile.getRefreshToken());
        AuthorizationUrl authorizationUrl = kindeClient.initClientSession(kindeToken).generatePortalUrl(kindeDomain, appBaseUrl + "/dashboard", "profile");
        return "redirect:" + authorizationUrl.getUrl().toString();
    }

    public String logout() {
        KindeClient kindeClient = KindeClientBuilder.builder().build();
        try {
            kindeClient.clientSession().logout();
        } catch (Exception e) {
            log.error("Error during logout: {}", e.getMessage());
            return "error";
        }
        return "redirect:/login";
    }
}