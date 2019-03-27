/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.shardingjdbc.jdbc.core.statement;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Collections2;
import lombok.Getter;
import org.apache.shardingsphere.core.PreparedQueryShardingEngine;
import org.apache.shardingsphere.core.execute.sql.execute.result.QueryResult;
import org.apache.shardingsphere.core.execute.sql.execute.result.StreamQueryResult;
import org.apache.shardingsphere.core.merge.MergeEngine;
import org.apache.shardingsphere.core.merge.MergeEngineFactory;
import org.apache.shardingsphere.core.optimize.GeneratedKey;
import org.apache.shardingsphere.core.parse.parser.sql.dal.DALStatement;
import org.apache.shardingsphere.core.parse.parser.sql.dml.insert.InsertStatement;
import org.apache.shardingsphere.core.parse.parser.sql.dql.DQLStatement;
import org.apache.shardingsphere.core.parse.parser.sql.dql.select.SelectStatement;
import org.apache.shardingsphere.core.route.SQLRouteResult;
import org.apache.shardingsphere.core.rule.ShardingRule;
import org.apache.shardingsphere.shardingjdbc.executor.BatchPreparedStatementExecutor;
import org.apache.shardingsphere.shardingjdbc.executor.PreparedStatementExecutor;
import org.apache.shardingsphere.shardingjdbc.jdbc.adapter.AbstractShardingPreparedStatementAdapter;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.ShardingContext;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.connection.ShardingConnection;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.resultset.GeneratedKeysResultSet;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.resultset.ShardingResultSet;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * PreparedStatement that support sharding.
 *
 * @author zhangliang
 * @author caohao
 * @author maxiaoguang
 * @author panjuan
 */
public final class ShardingPreparedStatement extends AbstractShardingPreparedStatementAdapter {
    
    @Getter
    private final ShardingConnection connection;
    
    private final String sql;
    
    private final PreparedQueryShardingEngine shardingEngine;
    
    private final PreparedStatementExecutor preparedStatementExecutor;
    
    private final BatchPreparedStatementExecutor batchPreparedStatementExecutor;
    
    private SQLRouteResult routeResult;
    
    private ResultSet currentResultSet;
    
    public ShardingPreparedStatement(final ShardingConnection connection, final String sql) {
        this(connection, sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT, false);
    }
    
    public ShardingPreparedStatement(final ShardingConnection connection, final String sql, final int resultSetType, final int resultSetConcurrency) {
        this(connection, sql, resultSetType, resultSetConcurrency, ResultSet.HOLD_CURSORS_OVER_COMMIT, false);
    }
    
    public ShardingPreparedStatement(final ShardingConnection connection, final String sql, final int autoGeneratedKeys) {
        this(connection, sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.HOLD_CURSORS_OVER_COMMIT, Statement.RETURN_GENERATED_KEYS == autoGeneratedKeys);
    }
    
    public ShardingPreparedStatement(final ShardingConnection connection, final String sql, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability) {
        this(connection, sql, resultSetType, resultSetConcurrency, resultSetHoldability, false);
    }
    
    private ShardingPreparedStatement(
            final ShardingConnection connection, final String sql, final int resultSetType, final int resultSetConcurrency, final int resultSetHoldability, final boolean returnGeneratedKeys) {
        this.connection = connection;
        this.sql = sql;
        ShardingContext shardingContext = connection.getShardingContext();
        shardingEngine = new PreparedQueryShardingEngine(sql, 
                shardingContext.getShardingRule(), shardingContext.getShardingProperties(), shardingContext.getMetaData(), shardingContext.getDatabaseType(), shardingContext.getParsingResultCache());
        preparedStatementExecutor = new PreparedStatementExecutor(resultSetType, resultSetConcurrency, resultSetHoldability, returnGeneratedKeys, connection);
        batchPreparedStatementExecutor = new BatchPreparedStatementExecutor(resultSetType, resultSetConcurrency, resultSetHoldability, returnGeneratedKeys, connection);
    }
    
    @Override
    public ResultSet executeQuery() throws SQLException {
        ResultSet result;
        try {
            clearPrevious();
            shard();
            initPreparedStatementExecutor();
            MergeEngine mergeEngine = MergeEngineFactory.newInstance(connection.getShardingContext().getDatabaseType(), connection.getShardingContext().getShardingRule(), 
                    routeResult.getSqlStatement(), connection.getShardingContext().getMetaData().getTable(), preparedStatementExecutor.executeQuery());
            result = getResultSet(mergeEngine);
        } finally {
            clearBatch();
        }
        currentResultSet = result;
        return result;
    }
    
    @Override
    public ResultSet getResultSet() throws SQLException {
        if (null != currentResultSet) {
            return currentResultSet;
        }
        if (1 == preparedStatementExecutor.getStatements().size() && routeResult.getSqlStatement() instanceof DQLStatement) {
            currentResultSet = preparedStatementExecutor.getStatements().iterator().next().getResultSet();
            return currentResultSet;
        }
        List<ResultSet> resultSets = new ArrayList<>(preparedStatementExecutor.getStatements().size());
        List<QueryResult> queryResults = new ArrayList<>(preparedStatementExecutor.getStatements().size());
        for (Statement each : preparedStatementExecutor.getStatements()) {
            ResultSet resultSet = each.getResultSet();
            resultSets.add(resultSet);
            ShardingRule shardingRule = connection.getShardingContext().getShardingRule();
            queryResults.add(new StreamQueryResult(resultSet, shardingRule.getAllActualTableNames(), shardingRule.getShardingEncryptorEngine()));
        }
        if (routeResult.getSqlStatement() instanceof SelectStatement || routeResult.getSqlStatement() instanceof DALStatement) {
            MergeEngine mergeEngine = MergeEngineFactory.newInstance(connection.getShardingContext().getDatabaseType(),
                    connection.getShardingContext().getShardingRule(), routeResult.getSqlStatement(), connection.getShardingContext().getMetaData().getTable(), queryResults);
            currentResultSet = getCurrentResultSet(resultSets, mergeEngine);
        }
        return currentResultSet;
    }
    
    private ShardingResultSet getResultSet(final MergeEngine mergeEngine) throws SQLException {
        return getCurrentResultSet(preparedStatementExecutor.getResultSets(), mergeEngine);
    }
    
    private ShardingResultSet getCurrentResultSet(final List<ResultSet> resultSets, final MergeEngine mergeEngine) throws SQLException {
        return new ShardingResultSet(resultSets, mergeEngine.merge(), this);
    }
    
    @Override
    public int executeUpdate() throws SQLException {
        try {
            clearPrevious();
            shard();
            initPreparedStatementExecutor();
            return preparedStatementExecutor.executeUpdate();
        } finally {
            clearBatch();
        }
    }
    
    @Override
    public boolean execute() throws SQLException {
        try {
            clearPrevious();
            shard();
            initPreparedStatementExecutor();
            return preparedStatementExecutor.execute();
        } finally {
            clearBatch();
        }
    }
    
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        Optional<GeneratedKey> generatedKey = getGeneratedKey();
        if (preparedStatementExecutor.isReturnGeneratedKeys() && generatedKey.isPresent()) {
            return new GeneratedKeysResultSet(routeResult.getGeneratedKey().getGeneratedKeys().iterator(), generatedKey.get().getColumnName(), this);
        }
        if (1 == preparedStatementExecutor.getStatements().size()) {
            return preparedStatementExecutor.getStatements().iterator().next().getGeneratedKeys();
        }
        return new GeneratedKeysResultSet();
    }
    
    private Optional<GeneratedKey> getGeneratedKey() {
        if (null != routeResult && routeResult.getSqlStatement() instanceof InsertStatement) {
            return Optional.fromNullable(routeResult.getGeneratedKey());
        }
        return Optional.absent();
    }
    
    private void initPreparedStatementExecutor() throws SQLException {
        preparedStatementExecutor.init(routeResult);
        setParametersForStatements();
    }
    
    private void setParametersForStatements() {
        for (int i = 0; i < preparedStatementExecutor.getStatements().size(); i++) {
            replaySetParameter((PreparedStatement) preparedStatementExecutor.getStatements().get(i), preparedStatementExecutor.getParameterSets().get(i));
        }
    }
    
    private void clearPrevious() throws SQLException {
        preparedStatementExecutor.clear();
    }
    
    @Override
    public void addBatch() {
        try {
            shard();
            batchPreparedStatementExecutor.addBatchForRouteUnits(routeResult);
        } finally {
            currentResultSet = null;
            clearParameters();
        }
    }
    
    private void shard() {
        routeResult = shardingEngine.shard(sql, getParameters());
    }
    
    @Override
    public int[] executeBatch() throws SQLException {
        try {
            initBatchPreparedStatementExecutor();
            return batchPreparedStatementExecutor.executeBatch();
        } finally {
            clearBatch();
        }
    }
    
    private void initBatchPreparedStatementExecutor() throws SQLException {
        batchPreparedStatementExecutor.init(routeResult);
        setBatchParametersForStatements();
    }
    
    private void setBatchParametersForStatements() throws SQLException {
        for (Statement each : batchPreparedStatementExecutor.getStatements()) {
            List<List<Object>> parameterSet = batchPreparedStatementExecutor.getParameterSet(each);
            for (List<Object> parameters : parameterSet) {
                replaySetParameter((PreparedStatement) each, parameters);
                ((PreparedStatement) each).addBatch();
            }
        }
    }
    
    @Override
    public void clearBatch() throws SQLException {
        currentResultSet = null;
        batchPreparedStatementExecutor.clear();
        clearParameters();
    }
    
    @SuppressWarnings("MagicConstant")
    @Override
    public int getResultSetType() {
        return preparedStatementExecutor.getResultSetType();
    }
    
    @SuppressWarnings("MagicConstant")
    @Override
    public int getResultSetConcurrency() {
        return preparedStatementExecutor.getResultSetConcurrency();
    }
    
    @Override
    public int getResultSetHoldability() {
        return preparedStatementExecutor.getResultSetHoldability();
    }
    
    @Override
    public boolean isAccumulate() {
        return !connection.getShardingContext().getShardingRule().isAllBroadcastTables(routeResult.getSqlStatement().getTables().getTableNames());
    }
    
    @Override
    public Collection<PreparedStatement> getRoutedStatements() {
        return Collections2.transform(preparedStatementExecutor.getStatements(), new Function<Statement, PreparedStatement>() {
            @Override
            public PreparedStatement apply(final Statement input) {
                return (PreparedStatement) input;
            }
        });
    }
}
