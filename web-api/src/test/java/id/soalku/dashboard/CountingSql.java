package id.soalku.dashboard;

import java.lang.reflect.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Observe real JDBC reads without replacing the queries or database with mocks. */
final class CountingSql {
    private static final ThreadLocal<List<String>> READS=new ThreadLocal<>();
    private static final ThreadLocal<Runnable> BEFORE_HISTORY=new ThreadLocal<>();
    static void start() { READS.set(new ArrayList<>()); }
    static List<String> stop() { var reads=List.copyOf(READS.get());READS.remove();return reads; }
    static void beforeNextHistory(Runnable work) { BEFORE_HISTORY.set(work); }
    static void reset() { READS.remove();BEFORE_HISTORY.remove(); }
    @TestConfiguration(proxyBeanMethods=false)
    static class Config {
        @Bean static BeanPostProcessor countDatabaseReads() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    return bean instanceof DataSource source && !(source instanceof CountingDataSource)?new CountingDataSource(source):bean;
                }
            };
        }
    }
    private static final class CountingDataSource extends DelegatingDataSource implements AutoCloseable {
        CountingDataSource(DataSource target) { super(target); }
        @Override public Connection getConnection() throws SQLException { return observe(super.getConnection()); }
        @Override public Connection getConnection(String user,String password) throws SQLException { return observe(super.getConnection(user,password)); }
        @Override public void close() throws Exception { if(getTargetDataSource() instanceof AutoCloseable target) target.close(); }
        private static Connection observe(Connection target) {
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement") && args!=null && args[0] instanceof String sql) {
                    String normalized=sql.stripLeading().toLowerCase(Locale.ROOT);
                    if(READS.get()!=null && (normalized.startsWith("select") || normalized.startsWith("with"))) READS.get().add(sql);
                    if(normalized.startsWith("select s.id,s.mode") && BEFORE_HISTORY.get()!=null) {
                        var work=BEFORE_HISTORY.get();BEFORE_HISTORY.remove();work.run();
                    }
                }
                try { return method.invoke(target,args); }
                catch(InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
    }
}
