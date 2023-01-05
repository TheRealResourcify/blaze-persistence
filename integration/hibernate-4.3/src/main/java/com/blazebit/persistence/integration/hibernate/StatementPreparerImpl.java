/*
 * Copyright 2014 - 2023 Blazebit.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.blazebit.persistence.integration.hibernate;

import com.blazebit.persistence.integration.hibernate.base.HibernateReturningResult;
import com.blazebit.persistence.spi.DbmsDialect;
import org.hibernate.ScrollMode;
import org.hibernate.cfg.Settings;
import org.hibernate.engine.jdbc.spi.JdbcCoordinator;
import org.hibernate.engine.jdbc.spi.LogicalConnectionImplementor;
import org.hibernate.engine.jdbc.spi.SqlExceptionHelper;
import org.hibernate.engine.jdbc.spi.StatementPreparer;
import org.hibernate.engine.spi.SessionFactoryImplementor;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * @author Christian Beikov
 * @since 1.2.0
 */
public class StatementPreparerImpl implements StatementPreparer {

    private JdbcCoordinator jdbcCoordinator;
    private SessionFactoryImplementor sessionFactoryImplementor;
    private DbmsDialect dbmsDialect;
    private String[][] columns;
    private int[] returningSqlTypes;
    private HibernateReturningResult<?> returningResult;

    public StatementPreparerImpl(JdbcCoordinator jdbcCoordinator, SessionFactoryImplementor sessionFactoryImplementor, DbmsDialect dbmsDialect, String[][] columns, int[] returningSqlTypes, HibernateReturningResult<?> returningResult) {
        this.jdbcCoordinator = jdbcCoordinator;
        this.sessionFactoryImplementor = sessionFactoryImplementor;
        this.dbmsDialect = dbmsDialect;
        this.columns = columns;
        this.returningSqlTypes = returningSqlTypes;
        this.returningResult = returningResult;
    }

    protected final Settings settings() {
        return sessionFactoryImplementor.getSettings();
    }

    protected final Connection connection() {
        return logicalConnection().getConnection();
    }

    protected final LogicalConnectionImplementor logicalConnection() {
        return jdbcCoordinator.getLogicalConnection();
    }

    protected final SqlExceptionHelper sqlExceptionHelper() {
        return jdbcCoordinator.getTransactionCoordinator()
            .getTransactionContext()
            .getTransactionEnvironment()
            .getJdbcServices()
            .getSqlExceptionHelper();
    }

    @Override
    public Statement createStatement() {
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public PreparedStatement prepareStatement(String sql) {
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, final boolean isCallable) {
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    private void checkAutoGeneratedKeysSupportEnabled() {
        // Not sure if we should respect this
//        if (!settings().isGetGeneratedKeysEnabled()) {
//            throw new AssertionFailure("getGeneratedKeys() support is not enabled");
//        }
    }

    @Override
    public PreparedStatement prepareStatement(String sql, final int autoGeneratedKeys) {
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, final String[] columnNames) {
        throw new UnsupportedOperationException("Not yet implemented!");
    }

    @Override
    public PreparedStatement prepareQueryStatement(String sql, final boolean isCallable, final ScrollMode scrollMode) {
        checkAutoGeneratedKeysSupportEnabled();
        jdbcCoordinator.executeBatch();
        PreparedStatement ps = new QueryStatementPreparationTemplate(sql) {

            public PreparedStatement doPrepare() throws SQLException {
                PreparedStatement ps;
                ps = connection().prepareStatement(sql, dbmsDialect.getPrepareFlags());
                return dbmsDialect.prepare(ps, returningSqlTypes);
            }
        }.prepareStatement();
        ps = (PreparedStatement) Proxy.newProxyInstance(ps.getClass().getClassLoader(), new Class[]{ PreparedStatement.class }, new PreparedStatementInvocationHandler(ps, dbmsDialect, columns, returningResult));
        jdbcCoordinator.registerLastQuery(ps);
        return ps;
    }

    /**
     * @author Christian Beikov
     * @since 1.2.0
     */
    private abstract class StatementPreparationTemplate {

        protected final String sql;

        protected StatementPreparationTemplate(String sql) {
            this.sql = jdbcCoordinator.getTransactionCoordinator().getTransactionContext().onPrepareStatement(sql);
        }

        public PreparedStatement prepareStatement() {
            try {
                jdbcCoordinator.getLogicalConnection().getJdbcServices().getSqlStatementLogger().logStatement(sql);

                final PreparedStatement preparedStatement;
                try {
                    jdbcCoordinator.getTransactionCoordinator().getTransactionContext().startPrepareStatement();
                    preparedStatement = doPrepare();
                    setStatementTimeout(preparedStatement);
                } finally {
                    jdbcCoordinator.getTransactionCoordinator().getTransactionContext().endPrepareStatement();
                }
                postProcess(preparedStatement);
                return preparedStatement;
            } catch (SQLException e) {
                throw sqlExceptionHelper().convert(e, "could not prepare statement", sql);
            }
        }

        protected abstract PreparedStatement doPrepare() throws SQLException;

        public void postProcess(PreparedStatement preparedStatement) throws SQLException {
            jdbcCoordinator.register(preparedStatement);
            logicalConnection().notifyObserversStatementPrepared();
        }

        private void setStatementTimeout(PreparedStatement preparedStatement) throws SQLException {
            final int remainingTransactionTimeOutPeriod = jdbcCoordinator.determineRemainingTransactionTimeOutPeriod();
            if (remainingTransactionTimeOutPeriod > 0) {
                preparedStatement.setQueryTimeout(remainingTransactionTimeOutPeriod);
            }
        }
    }

    /**
     * @author Christian Beikov
     * @since 1.2.0
     */
    private abstract class QueryStatementPreparationTemplate extends StatementPreparationTemplate {

        protected QueryStatementPreparationTemplate(String sql) {
            super(sql);
        }

        public void postProcess(PreparedStatement preparedStatement) throws SQLException {
            super.postProcess(preparedStatement);
            setStatementFetchSize(preparedStatement);
        }
    }

    private void setStatementFetchSize(PreparedStatement statement) throws SQLException {
        if (settings().getJdbcFetchSize() != null) {
            statement.setFetchSize(settings().getJdbcFetchSize());
        }
    }

}
