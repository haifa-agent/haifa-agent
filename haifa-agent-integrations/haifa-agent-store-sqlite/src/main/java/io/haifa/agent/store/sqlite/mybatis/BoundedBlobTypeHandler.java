package io.haifa.agent.store.sqlite.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

public final class BoundedBlobTypeHandler extends BaseTypeHandler<byte[]> {
    private final int maximumBytes;

    public BoundedBlobTypeHandler(int maximumBytes) {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.maximumBytes = maximumBytes;
    }

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, byte[] value, JdbcType jdbcType)
            throws SQLException {
        check(value);
        statement.setBytes(index, value);
    }

    @Override
    public byte[] getNullableResult(ResultSet result, String columnName) throws SQLException {
        return check(result.getBytes(columnName));
    }

    @Override
    public byte[] getNullableResult(ResultSet result, int columnIndex) throws SQLException {
        return check(result.getBytes(columnIndex));
    }

    @Override
    public byte[] getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return check(statement.getBytes(columnIndex));
    }

    private byte[] check(byte[] value) throws SQLException {
        if (value != null && value.length > maximumBytes) {
            throw new SQLException("BLOB exceeds the configured byte limit");
        }
        return value;
    }
}
