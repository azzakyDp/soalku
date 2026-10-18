package id.soalku.ai;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import id.soalku.core.QuestionGenerationException;
import static id.soalku.core.QuestionGenerationException.Reason.*;

/** Bound memory while receiving the body, rather than checking after allocation. */
final class BoundedResponseBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();
    private final ByteArrayOutputStream buffer=new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    @Override public CompletionStage<byte[]> getBody() { return result; }
    @Override public void onSubscribe(Flow.Subscription value) { subscription=value;value.request(1); }
    @Override public void onNext(List<ByteBuffer> chunks) {
        for(var chunk:chunks) {
            if ((long)buffer.size()+chunk.remaining()>512*1024) {
                subscription.cancel();result.completeExceptionally(new QuestionGenerationException(INVALID_OUTPUT,"Respons AI melebihi batas aman."));return;
            }
            byte[] bytes=new byte[chunk.remaining()];chunk.get(bytes);buffer.writeBytes(bytes);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
    @Override public void onComplete() { result.complete(buffer.toByteArray()); }
}
