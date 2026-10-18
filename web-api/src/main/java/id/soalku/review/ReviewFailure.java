package id.soalku.review;

final class ReviewFailure extends RuntimeException {
    final int status;
    final String code;
    ReviewFailure(int status,String code,String message) { super(message);this.status=status;this.code=code; }
}
