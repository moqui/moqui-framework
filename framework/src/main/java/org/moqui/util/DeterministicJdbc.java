package org.moqui.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;

/** Observe JDBC below entity APIs, including boot DDL, metadata and loader SQL. */
public final class DeterministicJdbc {
    private DeterministicJdbc() { }

    public static Connection wrap(Connection connection) {
        if (connection == null || !DeterministicClock.isEnabled()) return connection;
        return (Connection) proxy(connection, Connection.class, "");
    }

    private static Object proxy(Object target, Class<?> api, String preparedSql) {
        return Proxy.newProxyInstance(DeterministicJdbc.class.getClassLoader(), new Class<?>[]{api},
                (self, method, args) -> {
                    String action = method.getName();
                    if (action.equals("unwrap")) {
                        if (((Class<?>) args[0]).isInstance(self)) return self;
                        throw new SQLException("Vendor JDBC unwrap is unsupported in deterministic mode");
                    }
                    if (action.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(self);
                    boolean metadata = api == DatabaseMetaData.class && action.startsWith("get") &&
                            method.getReturnType() == ResultSet.class;
                    boolean observe = metadata || action.startsWith("execute") || action.equals("addBatch") ||
                            action.equals("commit") || action.equals("rollback") || action.equals("setAutoCommit") ||
                            action.equals("setSavepoint") || action.equals("releaseSavepoint");
                    String name = preparedSql;
                    if (args != null && args.length > 0 && args[0] instanceof String &&
                            (action.startsWith("execute") || action.equals("addBatch"))) name = (String) args[0];
                    if (metadata) {
                        // Metadata arguments are schema/table/column patterns, never bound record values.
                        name = java.util.Arrays.deepToString(args);
                    }
                    if (observe) DeterministicClock.trace("jdbc", action, name, "requested");
                    try {
                        Object result = method.invoke(target, args);
                        if (observe) DeterministicClock.trace("jdbc", action, name, "succeeded");
                        if (result instanceof Connection) return wrap((Connection) result);
                        String sql = args != null && args.length > 0 && args[0] instanceof String ? (String) args[0] : preparedSql;
                        if (result instanceof CallableStatement)
                            return proxy(result, CallableStatement.class, sql);
                        if (result instanceof PreparedStatement)
                            return proxy(result, PreparedStatement.class, sql);
                        if (result instanceof Statement) return proxy(result, Statement.class, "");
                        if (result instanceof DatabaseMetaData) return proxy(result, DatabaseMetaData.class, "");
                        return result;
                    } catch (InvocationTargetException e) {
                        if (observe) DeterministicClock.trace("jdbc", action, name, "failed");
                        throw e.getCause();
                    }
                });
    }
}
