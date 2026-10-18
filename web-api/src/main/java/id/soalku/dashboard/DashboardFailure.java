package id.soalku.dashboard;

final class DashboardFailure extends RuntimeException {
    final int status;
    final String code;
    DashboardFailure(int status,String code,String message) { super(message);this.status=status;this.code=code; }
}
