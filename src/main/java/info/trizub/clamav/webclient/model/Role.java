package info.trizub.clamav.webclient.model;

public enum Role {
    VIEWER, OPERATOR, ADMIN;

    public String asAuthority() {
        return "ROLE_" + name();
    }
}
