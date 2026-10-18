package id.soalku.review;

import id.soalku.core.StatusReview;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class ReviewDtos {
    private ReviewDtos() { }
    // Only editable fields are accepted; identity, ownership, status and audit are server controlled.
    public record EditRequest(@NotBlank @Size(max=3000) String pertanyaan,
            @NotNull @Size(min=3,max=4) List<@NotNull @Valid EditOption> opsi) { }
    public record EditOption(@NotNull @Positive Long id,@NotBlank @Size(max=1000) String teks,
            @NotNull Boolean benar) { }
    public record Option(Long id,int urutan,String teks,boolean benar) { }
    public record Question(Long id,Long bagianMateriId,String pertanyaan,Long gambarId,StatusReview statusReview,
            Long direviewOleh,Instant direviewAt,Instant createdAt,List<Option> opsi) { }
    public record Queue(List<Question> content,int page,int size,long totalElements,int totalPages) { }
}
