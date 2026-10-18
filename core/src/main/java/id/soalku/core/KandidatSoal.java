package id.soalku.core;

/** Input metadata only; total/salah are scoped to the current student and material section. */
public record KandidatSoal(long id,long bagianMateriId,StatusReview status,long totalJawaban,long salah) {
    public KandidatSoal {
        if(id<1 || bagianMateriId<1 || status==null || totalJawaban<0 || salah<0 || salah>totalJawaban)
            throw new IllegalArgumentException("Invalid question metadata or answer statistics");
    }
}
