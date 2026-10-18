package id.soalku.ai;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("soalku.gemini")
public record GeminiProperties(String apiKey,String model,URI baseUrl,Duration timeout,int questionsPerSection) {
    public GeminiProperties {
        if (apiKey==null) apiKey="";
        boolean official=baseUrl!=null && "https".equals(baseUrl.getScheme())
                && "generativelanguage.googleapis.com".equals(baseUrl.getHost()) && baseUrl.getPort()==-1;
        boolean localTest=baseUrl!=null && "http".equals(baseUrl.getScheme()) && "127.0.0.1".equals(baseUrl.getHost());
        if ((!official && !localTest) || !"/v1beta/".equals(baseUrl.getPath()) || baseUrl.getUserInfo()!=null
                || baseUrl.getQuery()!=null || baseUrl.getFragment()!=null || apiKey.contains("\r") || apiKey.contains("\n")
                || model==null || !model.matches("gemini-[A-Za-z0-9._-]{1,80}")
                || timeout==null || timeout.compareTo(Duration.ofMillis(100))<0 || timeout.compareTo(Duration.ofSeconds(30))>0
                || questionsPerSection<1 || questionsPerSection>10)
            throw new IllegalArgumentException("Konfigurasi Gemini tidak valid.");
    }
    public boolean configured() { return !apiKey.isBlank(); }
    @Override public String toString() { return "GeminiProperties[apiKey=<redacted>, model="+model+"]"; }
}
