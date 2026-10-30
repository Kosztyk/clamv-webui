package info.trizub.clamav.webclient.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional providers; both disabled by default. Passwords must be supplied through secrets/env. */
@ConfigurationProperties(prefix = "clamav.identity")
public class IdentityProperties {
    private final Ldap ldap = new Ldap();
    private final Oidc oidc = new Oidc();
    public Ldap getLdap() { return ldap; }
    public Oidc getOidc() { return oidc; }

    public static class RoleGroups {
        private String viewer = "";
        private String operator = "";
        private String admin = "";
        public String getViewer() { return viewer; }
        public void setViewer(String v) { viewer = v; }
        public String getOperator() { return operator; }
        public void setOperator(String v) { operator = v; }
        public String getAdmin() { return admin; }
        public void setAdmin(String v) { admin = v; }
    }
    public static class Ldap {
        private boolean enabled = false;
        private String url = "";
        private String baseDn = "";
        private String managerDn = "";
        private String managerPassword = "";
        private String managerPasswordFile = "";
        private String userSearchBase = "";
        private String userSearchFilter = "(uid={0})";
        private String groupSearchBase = "";
        // Blank preserves the legacy groupMemberAttribute-based DN lookup.
        private String groupSearchFilter = "";
        private String groupMemberAttribute = "member";
        private String groupNameAttribute = "cn";
        private boolean nestedGroups = false;
        private int maxNestedDepth = 3;
        private boolean startTls = false;
        private boolean pooled = false;
        private String referrals = "ignore";
        private int connectTimeoutMillis = 5000;
        private int readTimeoutMillis = 5000;
        private final RoleGroups groups = new RoleGroups();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { enabled=v; }
        public String getUrl() { return url; }
        public void setUrl(String v) { url=v; }
        public String getBaseDn() { return baseDn; }
        public void setBaseDn(String v) { baseDn=v; }
        public String getManagerDn() { return managerDn; }
        public void setManagerDn(String v) { managerDn=v; }
        public String getManagerPassword() { return managerPassword; }
        public void setManagerPassword(String v) { managerPassword=v; }
        public String getManagerPasswordFile() { return managerPasswordFile; }
        public void setManagerPasswordFile(String v) { managerPasswordFile=v; }
        public String getUserSearchBase() { return userSearchBase; }
        public void setUserSearchBase(String v) { userSearchBase=v; }
        public String getUserSearchFilter() { return userSearchFilter; }
        public void setUserSearchFilter(String v) { userSearchFilter=v; }
        public String getGroupSearchBase() { return groupSearchBase; }
        public void setGroupSearchBase(String v) { groupSearchBase=v; }
        public String getGroupSearchFilter() { return groupSearchFilter; }
        public void setGroupSearchFilter(String v) { groupSearchFilter=v; }
        public String getGroupMemberAttribute() { return groupMemberAttribute; }
        public void setGroupMemberAttribute(String v) { groupMemberAttribute=v; }
        public String getGroupNameAttribute() { return groupNameAttribute; }
        public void setGroupNameAttribute(String v) { groupNameAttribute=v; }
        public boolean isNestedGroups() { return nestedGroups; }
        public void setNestedGroups(boolean v) { nestedGroups=v; }
        public int getMaxNestedDepth() { return maxNestedDepth; }
        public void setMaxNestedDepth(int v) { maxNestedDepth=v; }
        public boolean isStartTls() { return startTls; }
        public void setStartTls(boolean v) { startTls=v; }
        public boolean isPooled() { return pooled; }
        public void setPooled(boolean v) { pooled=v; }
        public String getReferrals() { return referrals; }
        public void setReferrals(String v) { referrals=v; }
        public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
        public void setConnectTimeoutMillis(int v) { connectTimeoutMillis=v; }
        public int getReadTimeoutMillis() { return readTimeoutMillis; }
        public void setReadTimeoutMillis(int v) { readTimeoutMillis=v; }
        public RoleGroups getGroups() { return groups; }
    }
    public static class Oidc {
        private boolean enabled = false;
        private String registrationId = "clamav";
        private String issuer = "";
        private String clientId = "";
        private String clientSecret = "";
        private String clientSecretFile = "";
        private java.util.List<String> scopes = java.util.List.of("openid", "profile", "email", "groups");
        private String groupsClaim = "groups";
        private String buttonLabel = "Sign in with SSO";
        private String logoutMode = "local";
        private final RoleGroups groups = new RoleGroups();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { enabled=v; }
        public String getRegistrationId() { return registrationId; }
        public void setRegistrationId(String v) { registrationId=v; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String v) { issuer=v; }
        public String getClientId() { return clientId; }
        public void setClientId(String v) { clientId=v; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String v) { clientSecret=v; }
        public String getClientSecretFile() { return clientSecretFile; }
        public void setClientSecretFile(String v) { clientSecretFile=v; }
        public java.util.List<String> getScopes() { return scopes; }
        public void setScopes(java.util.List<String> v) { scopes = v == null ? java.util.List.of() : java.util.List.copyOf(v); }
        public String getGroupsClaim() { return groupsClaim; }
        public void setGroupsClaim(String v) { groupsClaim=v; }
        public String getButtonLabel() { return buttonLabel; }
        public void setButtonLabel(String v) { buttonLabel=v; }
        public String getLogoutMode() { return logoutMode; }
        public void setLogoutMode(String v) { logoutMode=v; }
        public RoleGroups getGroups() { return groups; }
    }
}
