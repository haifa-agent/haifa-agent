package io.haifa.agent.store.sqlite.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

public final class InstantEpochMillisTypeHandler extends BaseTypeHandler<Instant> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Instant value, JdbcType jdbcType)
            throws SQLException {
        statement.setLong(index, value.toEpochMilli());
    }

    @Override
    public Instant getNullableResult(ResultSet result, String columnName) throws SQLException {
        long value = result.getLong(columnName);
        return result.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    @Override
    public Instant getNullableResult(ResultSet result, int columnIndex) throws SQLException {
        long value = result.getLong(columnIndex);
        return result.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    @Override
    public Instant getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        long value = statement.getLong(columnIndex);
        return statement.wasNull() ? null : Instant.ofEpochMilli(value);
    }
}
