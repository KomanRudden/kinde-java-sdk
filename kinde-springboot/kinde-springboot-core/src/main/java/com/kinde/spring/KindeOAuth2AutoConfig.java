package com.kinde.spring;

import com.kinde.spring.config.KindeOAuth2Properties;
import com.kinde.spring.sdk.KindeSdkClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.ConditionalOnDefaultWebSecurity;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;

@AutoConfiguration
@ConditionalOnKindeClientProperties
@EnableConfigurationProperties(KindeOAuth2Properties.class)
@ConditionalOnClass({ EnableWebSecurity.class, ClientRegistration.class })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(AuthorityProvidersConfig.class)
class KindeOAuth2AutoConfig {

    private KindeSdkClient kindeSdkClient;

    @Autowired
    public void setKindeSdkClient(KindeSdkClient kindeSdkClient) {
        this.kindeSdkClient = kindeSdkClient;
    }

    @Bean
    @ConditionalOnProperty(name = "okta.oauth2.post-logout-redirect-uri")
    OidcClientInitiatedLogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository clientRegistrationRepository) {
        OidcClientInitiatedLogoutSuccessHandler successHandler = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        String logoutUri = kindeSdkClient.getClient().kindeConfig().logoutRedirectUri();
        successHandler.setPostLogoutRedirectUri((logoutUri.startsWith("/") ? "{baseUrl}" : "") + logoutUri);
        return successHandler;
    }

    @Bean
    @ConditionalOnMissingBean(name="oAuth2UserService")
    OAuth2UserService<OAuth2UserRequest, OAuth2User> oAuth2UserService(Collection<AuthoritiesProvider> authoritiesProviders) {
        return new KindeOAuth2UserService(authoritiesProviders,this.kindeSdkClient);
    }

    @Bean
    @ConditionalOnMissingBean(name="oidcUserService")
    OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(
        @Qualifier("oAuth2UserService") OAuth2UserService<OAuth2UserRequest, OAuth2User> oAuth2UserService,
        Collection<AuthoritiesProvider> authoritiesProviders) {
        return new KindeOidcUserService(oAuth2UserService, authoritiesProviders, kindeSdkClient);
    }

    @Configuration
    @ConditionalOnClass({ EnableWebSecurity.class, ClientRegistration.class })
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = "kinde.oauth2.auto-config.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(name = { "securityFilterChain", "applicationSecurityFilterChain" })
    static class OAuth2SecurityFilterChainConfiguration {

        @Bean
        SecurityFilterChain oauth2SecurityFilterChain(HttpSecurity http, ClientRegistrationRepository clientRegistrationRepository) throws Exception {
            // as of Spring Security 5.4 the default chain uses oauth2Login OR a JWT resource server (NOT both)
            // this does the same as both defaults merged together (and provides the previous behavior)
            http.authorizeRequests((requests) -> requests.anyRequest().authenticated());
            Kinde.configureOAuth2WithPkce(http, clientRegistrationRepository);
            http.oauth2Client();
            http.oauth2ResourceServer(OAuth2ResourceServerConfigurer::jwt);
            return http.build();
        }
    }
}
