package com.kubemind.auth.oidc;

import com.kubemind.auth.User;
import com.kubemind.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * Just-in-time provisioning: the first successful OIDC login for a given
 * identity creates a row in the same {@code users} table local logins use, so
 * UserService/UserController/the Users admin page/AuditService all keep
 * working completely unchanged — an OIDC account is just a user row that
 * happens to have no password.
 */
@Service
public class KubemindOidcUserService extends OidcUserService {

    private static final Logger log = LoggerFactory.getLogger(KubemindOidcUserService.class);

    private final UserRepository userRepository;
    private final KubemindOidcProperties properties;

    public KubemindOidcUserService(UserRepository userRepository, KubemindOidcProperties properties) {
        this.userRepository = userRepository;
        this.properties = properties;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser delegate = super.loadUser(userRequest);
        String username = resolveUsername(delegate);
        User user = findOrProvision(username, delegate);
        return new KubemindOidcUser(delegate, username, user.getRole());
    }

    // Package-private (not private) so the priority order is directly
    // testable without going through loadUser()'s real userinfo HTTP call.
    // Arrays.asList, not List.of — preferred_username/email are routinely
    // absent claims, and List.of rejects null elements outright.
    String resolveUsername(OidcUser delegate) {
        for (String candidate : Arrays.asList(delegate.getPreferredUsername(), delegate.getEmail(), delegate.getSubject())) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        // Unreachable in practice — every OIDC token has a `sub` claim by spec —
        // but loadUser()'s contract requires a real value, not a silent fallback.
        throw new OAuth2AuthenticationException(
            new OAuth2Error("missing_identity", "OIDC token has no preferred_username, email, or sub claim", null));
    }

    @Transactional
    protected User findOrProvision(String username, OidcUser delegate) {
        return userRepository.findByUsername(username)
            .map(existing -> requireOidcOwned(existing, username))
            .orElseGet(() -> provision(username, delegate));
    }

    /** An existing row with this username that ISN'T ours (a local password
     *  account) must never be silently taken over by an OIDC login. */
    private User requireOidcOwned(User existing, String username) {
        if (!"oidc".equals(existing.getIdentityProvider())) {
            throw new OAuth2AuthenticationException(new OAuth2Error("username_collision",
                "A local account named '" + username + "' already exists", null));
        }
        return existing;
    }

    /** Role is decided once, here, and never re-derived on later logins — see
     *  KubemindOidcProperties' adminGroup javadoc for why. */
    private User provision(String username, OidcUser delegate) {
        String role = isInAdminGroup(delegate) ? "ADMIN" : "USER";
        User user = userRepository.save(User.oidcProvisioned(username, role));
        log.info("Provisioned OIDC user '{}' with role {}", username, role);
        return user;
    }

    private boolean isInAdminGroup(OidcUser delegate) {
        if (!StringUtils.hasText(properties.adminGroup())) {
            return false;
        }
        List<String> groups = delegate.getClaimAsStringList(properties.groupsClaim());
        return groups != null && groups.contains(properties.adminGroup());
    }
}
