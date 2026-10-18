package id.soalku.ai;

import id.soalku.core.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class GeminiGeneratorServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final ConcurrentLinkedQueue<Reply> replies=new ConcurrentLinkedQueue<>();
    private final AtomicInteger calls=new AtomicInteger();
    private final AtomicReference<JsonNode> sent=new AtomicReference<>();
    private final AtomicReference<String> keyHeader=new AtomicReference<>();
    private final AtomicReference<CountDownLatch> hold=new AtomicReference<>();
    record Reply(int status,String body,long delay) { }
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newFixedThreadPool(4);server.setExecutor(executor);
        server.createContext("/v1beta/models/gemini-3.8-flash:generateContent",exchange -> {
            calls.incrementAndGet();keyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            sent.set(json.readTree(exchange.getRequestBody().readNBytes(16*1024*1024)));
            var reply=replies.remove();byte[] output=reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(reply.status(),output.length);
            try {
                var latch=hold.get();if(latch!=null && !latch.await(3,TimeUnit.SECONDS)) throw new java.io.IOException("Test gate timed out");
                if(reply.delay()>0) Thread.sleep(reply.delay());
                exchange.getResponseBody().write(output);
            } catch(InterruptedException failure) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });server.start();
    }
    @AfterEach void stop() { server.stop(0);executor.shutdownNow(); }
    private GeminiProperties properties(String key,Duration timeout) {
        return new GeminiProperties(key,"gemini-3.8-flash",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1beta/"),timeout,1);
    }
    private QuestionGeneratorService.Request request(Jenjang level) {
        return new QuestionGeneratorService.Request(level,"Tumbuhan","Akar menyerap air.",List.of(),1);
    }
    private String generated(int options) throws Exception {
        return json.writeValueAsString(Map.of("ringkasan","Akar menyerap air.","soal",List.of(question(options))));
    }
    private Map<String,Object> question(int options) {
        var value=new LinkedHashMap<String,Object>();value.put("pertanyaan","Apa fungsi akar?");
        value.put("opsi",options==3 ? List.of("Menyerap air","Terbang","Berenang") : List.of("Menyerap air","Terbang","Berenang","Berlari"));
        value.put("jawaban_benar",1);value.put("gambar_id",null);return value;
    }
    private void respond(int status,String content,long delay) throws Exception {
        String body=status==200 ? json.writeValueAsString(Map.of("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text",content))))))) : content;
        replies.add(new Reply(status,body,delay));
    }
    @Test void realHttpProducesSdAndSmpQuestionsAndUsesHeaderKeyAndSchema() throws Exception {
        var service=new GeminiGeneratorService(properties("test-key-only",Duration.ofSeconds(2)));
        respond(200,generated(3),0);var sd=service.generate(request(Jenjang.SD));
        assertEquals(3,sd.soal().getFirst().getOpsi().size());assertEquals(StatusReview.MENUNGGU,sd.soal().getFirst().getStatusReview());
        assertEquals("test-key-only",keyHeader.get());
        var schema=sent.get().path("generationConfig").path("responseFormat").path("text").path("schema");
        assertEquals("APPLICATION_JSON",sent.get().path("generationConfig").path("responseFormat").path("text").path("mimeType").asText());
        assertEquals(3,schema.path("properties").path("soal").path("items").path("properties").path("opsi").path("minItems").asInt());
        assertTrue(sent.get().path("contents").get(0).path("parts").get(0).path("text").asText().contains("maks 5 kalimat"));
        respond(200,generated(4),0);assertEquals(4,service.generate(request(Jenjang.SMP)).soal().getFirst().getOpsi().size());
        assertEquals("UP",service.health().getStatus().getCode());
        assertFalse(service.toString().contains("test-key-only"));assertFalse(properties("test-key-only",Duration.ofSeconds(2)).toString().contains("test-key-only"));
    }
    @Test void rejectsMalformedJsonWrongOptionsWrongKeyUnknownFieldsAndForeignImage() throws Exception {
        var service=new GeminiGeneratorService(properties("test-key-only",Duration.ofSeconds(2)));
        var invalid=new ArrayList<String>();invalid.add("{bad}");invalid.add(generated(4));invalid.add(generated(3)+" {}");
        invalid.add(generated(3).replace("\"jawaban_benar\":1","\"jawaban_benar\":0"));
        invalid.add(generated(3).replace("\"gambar_id\":null","\"gambar_id\":999"));
        invalid.add(generated(3).replace("\"gambar_id\":null","\"gambar_id\":null,\"status_review\":\"DISETUJUI\""));
        invalid.add(generated(3).replace("\"ringkasan\":","\"ringkasan\":\"duplicate\",\"ringkasan\":"));
        invalid.add(generated(3).replace("Akar menyerap air.","Satu kalimat. Dua kalimat. Tiga kalimat. Empat kalimat. Lima kalimat. Enam kalimat."));
        for(var content:invalid) {
            respond(200,content,0);
            var failure=assertThrows(QuestionGenerationException.class,() -> service.generate(request(Jenjang.SD)));
            assertEquals(QuestionGenerationException.Reason.INVALID_OUTPUT,failure.reason());
        }
    }
    @Test void imageBytesAndWhitelistAreSentAndValidated() throws Exception {
        var request=new QuestionGeneratorService.Request(Jenjang.SD,"Akar","Lihat gambar akar.",List.of(new QuestionGeneratorService.Image(7L,new byte[]{1,2,3})),1);
        respond(200,generated(3).replace("\"gambar_id\":null","\"gambar_id\":7"),0);
        var result=new GeminiGeneratorService(properties("test-key-only",Duration.ofSeconds(2))).generate(request);
        assertEquals(7L,result.soal().getFirst().getGambarId());
        assertArrayEquals(new byte[]{1,2,3},Base64.getDecoder().decode(sent.get().path("contents").get(0).path("parts").get(2).path("inlineData").path("data").asText()));
    }
    @Test void rateLimitTimeoutIncludingSlowBodyAndMissingKeyAreExplicit() throws Exception {
        var service=new GeminiGeneratorService(properties("test-key-only",Duration.ofMillis(200)));
        respond(429,"{\"error\":\"quota\"}",0);
        assertEquals(QuestionGenerationException.Reason.UNAVAILABLE,assertThrows(QuestionGenerationException.class,() -> service.generate(request(Jenjang.SD))).reason());
        respond(200,generated(3),1000);long start=System.nanoTime();
        assertEquals(QuestionGenerationException.Reason.TIMEOUT,assertThrows(QuestionGenerationException.class,() -> service.generate(request(Jenjang.SD))).reason());
        assertTrue(Duration.ofNanos(System.nanoTime()-start).compareTo(Duration.ofSeconds(2))<0);
        assertEquals("DOWN",service.health().getStatus().getCode());
        var missing=new GeminiGeneratorService(properties("",Duration.ofSeconds(2)));int before=calls.get();
        assertEquals(QuestionGenerationException.Reason.NOT_CONFIGURED,assertThrows(QuestionGenerationException.class,() -> missing.generate(request(Jenjang.SD))).reason());
        assertEquals(before,calls.get());assertEquals("UNKNOWN",missing.health().getStatus().getCode());
    }
    @Test void oversizedResponseIsStoppedAndRedirectDoesNotForwardKey() throws Exception {
        var service=new GeminiGeneratorService(properties("test-key-only",Duration.ofSeconds(2)));
        replies.add(new Reply(200,"x".repeat(512*1024+1),0));
        assertEquals(QuestionGenerationException.Reason.INVALID_OUTPUT,assertThrows(QuestionGenerationException.class,() -> service.generate(request(Jenjang.SD))).reason());
        replies.add(new Reply(302,"{}",0));
        assertEquals(QuestionGenerationException.Reason.UNAVAILABLE,assertThrows(QuestionGenerationException.class,() -> service.generate(request(Jenjang.SD))).reason());
        assertEquals(2,calls.get());
    }
    @Test void incompleteCandidateCannotBecomeQuestions() throws Exception {
        var service=new GeminiGeneratorService(properties("test-key-only",Duration.ofSeconds(2)));
        replies.add(new Reply(200,json.writeValueAsString(Map.of("candidates",List.of(Map.of("finishReason","MAX_TOKENS")))),0));
        assertEquals(QuestionGenerationException.Reason.INVALID_OUTPUT,assertThrows(QuestionGenerationException.class,() -> service.generate(request(Jenjang.SD))).reason());
    }
    @Test void capacityIsBoundedAndRecoveredAfterRequestsComplete() throws Exception {
        var service=new GeminiGeneratorService(properties("test-key-only",Duration.ofSeconds(3)));
        var latch=new CountDownLatch(1);hold.set(latch);
        for(int i=0;i<4;i++) respond(200,generated(3),0);
        try(var callers=Executors.newFixedThreadPool(4)) {
            var tasks=new ArrayList<Future<QuestionGeneratorService.GeneratedSection>>();
            for(int i=0;i<4;i++) tasks.add(callers.submit(() -> service.generate(request(Jenjang.SD))));
            try {
                for(int i=0;i<200 && calls.get()<4;i++) Thread.sleep(10);
                assertEquals(4,calls.get());
                assertEquals(QuestionGenerationException.Reason.BUSY,assertThrows(QuestionGenerationException.class,() -> service.generate(request(Jenjang.SD))).reason());
            } finally { hold.set(null);latch.countDown(); }
            for(var task:tasks) assertEquals(1,task.get(4,TimeUnit.SECONDS).soal().size());
        }
        respond(200,generated(3),0);assertEquals(1,service.generate(request(Jenjang.SD)).soal().size());assertEquals(5,calls.get());
    }
}
