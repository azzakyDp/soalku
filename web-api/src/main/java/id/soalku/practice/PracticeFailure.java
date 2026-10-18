package id.soalku.practice;

final class PracticeFailure extends RuntimeException {
    final int status;
    final String code;
    PracticeFailure(int status,String code,String message) { this(status,code,message,null); }
    PracticeFailure(int status,String code,String message,Throwable cause) { super(message,cause);this.status=status;this.code=code; }
}
