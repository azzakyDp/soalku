package id.soalku.ai;

final class GenerationFailure extends RuntimeException {
    final int status;
    final String code;
    GenerationFailure(int status,String code,String message) { super(message);this.status=status;this.code=code; }
}
