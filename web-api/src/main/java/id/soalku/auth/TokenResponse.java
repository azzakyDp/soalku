package id.soalku.auth;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    @Override
    public String toString() { return "TokenResponse[accessToken=REDACTED]"; }
}
