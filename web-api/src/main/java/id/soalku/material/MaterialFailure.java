package id.soalku.material;

final class MaterialFailure extends RuntimeException {
    final int status;
    final String code;
    MaterialFailure(int status,String code,String message) { super(message); this.status=status; this.code=code; }
    MaterialFailure(int status,String code,String message,Throwable cause) { super(message,cause); this.status=status; this.code=code; }
}
