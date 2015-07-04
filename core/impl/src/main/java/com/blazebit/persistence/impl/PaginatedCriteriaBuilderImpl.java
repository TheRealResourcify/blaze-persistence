/*
 * Copyright 2014 Blazebit.
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
package com.blazebit.persistence.impl;

import com.blazebit.persistence.impl.keyset.KeysetPageImpl;
import com.blazebit.persistence.impl.keyset.KeysetPaginationHelper;
import com.blazebit.persistence.impl.keyset.KeysetMode;
import com.blazebit.persistence.CaseWhenBuilder;
import com.blazebit.persistence.KeysetPage;
import com.blazebit.persistence.ObjectBuilder;
import com.blazebit.persistence.PagedList;
import com.blazebit.persistence.PaginatedCriteriaBuilder;
import com.blazebit.persistence.QueryBuilder;
import com.blazebit.persistence.SelectObjectBuilder;
import com.blazebit.persistence.SimpleCaseWhenBuilder;
import com.blazebit.persistence.SubqueryInitiator;
import com.blazebit.persistence.impl.builder.object.DelegatingKeysetExtractionObjectBuilder;
import com.blazebit.persistence.impl.builder.object.KeysetExtractionObjectBuilder;
import com.blazebit.persistence.impl.keyset.SimpleKeysetLink;
import com.blazebit.persistence.spi.QueryTransformer;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.Metamodel;

/**
 *
 * @author Christian Beikov
 * @author Moritz Becker
 * @since 1.0
 */
public class PaginatedCriteriaBuilderImpl<T> extends AbstractQueryBuilder<T, PaginatedCriteriaBuilder<T>> implements PaginatedCriteriaBuilder<T> {

    private static final String ENTITY_PAGE_POSITION_PARAMETER_NAME = "_entityPagePositionParameter";
    private static final String PAGE_POSITION_ID_QUERY_ALIAS_PREFIX = "_page_position_";

    private boolean keysetExtraction;
    private final KeysetPage keysetPage;

    // Mutable state
    private boolean needsCheck = true;

    private final Object entityId;
    private int firstResult;
    private int firstRow;
    private final int pageSize;
    private boolean needsNewIdList;
    private final KeysetMode keysetMode;

    // Cache
    private String cachedCountQueryString;
    private String cachedIdQueryString;

    public PaginatedCriteriaBuilderImpl(AbstractQueryBuilder<T, ? extends QueryBuilder<T, ?>> baseBuilder, boolean keysetExtraction, KeysetPage keysetPage, Object entityId, int pageSize) {
        super(baseBuilder);
        if (firstRow < 0) {
            throw new IllegalArgumentException("firstRow may not be negative");
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize may not be zero or negative");
        }
        this.keysetExtraction = keysetExtraction;
        this.keysetPage = keysetPage;
        this.firstResult = -1;
        this.firstRow = -1;
        this.entityId = entityId;
        this.pageSize = pageSize;
        // We do offset pagination to scroll to the page where an entity is
        this.keysetMode = KeysetMode.NONE;
        this.keysetManager.setKeysetLink(null);
    }

    public PaginatedCriteriaBuilderImpl(AbstractQueryBuilder<T, ? extends QueryBuilder<T, ?>> baseBuilder, boolean keysetExtraction, KeysetPage keysetPage, int firstRow, int pageSize) {
        super(baseBuilder);
        if (firstRow < 0) {
            throw new IllegalArgumentException("firstRow may not be negative");
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize may not be zero or negative");
        }
        this.keysetExtraction = keysetExtraction;
        this.keysetPage = keysetPage;
        this.firstResult = firstRow;
        this.firstRow = firstRow;
        this.entityId = null;
        this.pageSize = pageSize;
        this.keysetMode = KeysetPaginationHelper.getKeysetMode(keysetPage, firstRow, pageSize);
        
        if (keysetMode == KeysetMode.NONE) {
            this.keysetManager.setKeysetLink(null);
        } else if (keysetMode == KeysetMode.NEXT) {
            this.keysetManager.setKeysetLink(new SimpleKeysetLink(keysetPage.getHighest(), keysetMode));
        } else {
            this.keysetManager.setKeysetLink(new SimpleKeysetLink(keysetPage.getLowest(), keysetMode));
        }
    }

    @Override
    public PaginatedCriteriaBuilder<T> withKeysetExtraction(boolean keysetExtraction) {
        this.keysetExtraction = keysetExtraction;
        return this;
    }

    @Override
    public boolean isKeysetExtraction() {
        return keysetExtraction;
    }

    @Override
    public PagedList<T> getResultList() {
        prepareAndCheck();

        String countQueryString = getPageCountQueryString0();
        long totalSize;
        
        if (entityId == null) {
            // No reference entity id, so just do a simple count query
            TypedQuery<Long> countQuery = em.createQuery(countQueryString, Long.class);
            parameterizeQuery(countQuery);

            totalSize = countQuery.getSingleResult();
        } else {
            // There is a reference entity id, so we need to extract the page position
            TypedQuery<Object[]> countQuery = em.createQuery(countQueryString, Object[].class);
            parameterizeQuery(countQuery);
            
            Object[] result = countQuery.getSingleResult();
            totalSize = (Long) result[0];
            
            if (result[1] == null) {
                // If the reference entity id is not contained (i.e. has no position), we return this special value
                firstResult = -1;
                firstRow = 0;
            } else {
                // The page position is numbered from 1 so we need to correct this here
                int position = ((Long) result[1]).intValue() - 1;
                firstResult = firstRow = position == 0 ? 0 : position - (position % pageSize);
            }
        }

        if (totalSize == 0L) {
            return new PagedListImpl<T>(null, totalSize, firstResult, pageSize);
        }

        if (!joinManager.hasCollections()) {
            return getResultListViaObjectQuery(totalSize);
        } else {
            return getResultListViaIdQuery(totalSize);
        }
    }

    @Override
    public String getPageCountQueryString() {
        prepareAndCheck();
        return getPageCountQueryString0();
    }

    private String getPageCountQueryString0() {
        if (cachedCountQueryString == null) {
            cachedCountQueryString = getPageCountQueryString1();
        }

        return cachedCountQueryString;
    }

    @Override
    public String getPageIdQueryString() {
        prepareAndCheck();
        return getPageIdQueryString0();
    }

    private String getPageIdQueryString0() {
        if (cachedIdQueryString == null) {
            cachedIdQueryString = getPageIdQueryString1();
        }

        return cachedIdQueryString;
    }

    @Override
    public String getQueryString() {
        prepareAndCheck();
        return getQueryString0();
    }

    private String getQueryString0() {
        if (cachedQueryString == null) {
            if (!joinManager.hasCollections()) {
                cachedQueryString = getObjectQueryString1();
            } else {
                cachedQueryString = getQueryString1();
            }
        }

        return cachedQueryString;
    }

    @Override
    protected void clearCache() {
        super.clearCache();
        cachedCountQueryString = null;
        cachedIdQueryString = null;
    }

    @Override
    protected void prepareAndCheck() {
        if (!needsCheck) {
            return;
        }

        verifyBuilderEnded();
        if (!orderByManager.hasOrderBys()) {
            throw new IllegalStateException("Pagination requires at least one order by item!");
        }

        applyImplicitJoins();
        applyExpressionTransformers();
        
        // Paginated criteria builders always need the last order by expression to be unique
        Metamodel m = em.getMetamodel();
        List<OrderByExpression> orderByExpressions = orderByManager.getOrderByExpressions(m);
        if (!orderByExpressions.get(orderByExpressions.size() - 1).isUnique()) {
            throw new IllegalStateException("The last order by item must be unique!");
        }
        
        if (keysetManager.hasKeyset()) {
            keysetManager.initialize(orderByExpressions);
        }

        needsNewIdList = keysetExtraction || orderByManager.hasComplexOrderBys();
        
        // No need to do the check again if no mutation occurs
        needsCheck = false;
    }

    private PagedList<T> getResultListViaObjectQuery(long totalSize) {
        String queryString = getQueryString0();
        Class<?> expectedResultType;
        
        // When the keyset is included the query obviously produces an array
        if (keysetExtraction) {
            expectedResultType = Object[].class;
        } else {
            expectedResultType = selectManager.getExpectedQueryResultType();
        }
        
        TypedQuery<T> query = (TypedQuery<T>) em.createQuery(queryString, expectedResultType)
                .setMaxResults(pageSize);

        if (keysetMode == KeysetMode.NONE) {
            query.setFirstResult(firstRow);
        }

        KeysetExtractionObjectBuilder<T> objectBuilder = null;
        ObjectBuilder<T> transformerObjectBuilder = selectManager.getSelectObjectBuilder();

        if (keysetExtraction) {
            int keysetSize = orderByManager.getOrderByCount();

            if (transformerObjectBuilder == null) {
                objectBuilder = new KeysetExtractionObjectBuilder<T>(keysetSize);
            } else {
                objectBuilder = new DelegatingKeysetExtractionObjectBuilder<T>(transformerObjectBuilder, keysetSize);
            }

            transformerObjectBuilder = objectBuilder;
        }

        if (transformerObjectBuilder != null) {
            for (QueryTransformer transformer : cbf.getQueryTransformers()) {
            	query = transformer.transformQuery((TypedQuery<T>) query, transformerObjectBuilder);
            }
        }

        parameterizeQuery(query);
        List<T> result = query.getResultList();

        if (result.isEmpty()) {
            KeysetPage newKeysetPage = null;
            if (keysetMode == KeysetMode.NEXT) {
                // When we scroll over the last page to a non existing one, we reuse the current keyset
                newKeysetPage = keysetPage;
            }

            return new PagedListImpl<T>(newKeysetPage, totalSize, firstResult, pageSize);
        }

        KeysetPage newKeyset = null;

        if (keysetExtraction) {
            Serializable[] lowest = objectBuilder.getLowest();
            Serializable[] highest = objectBuilder.getHighest();
            newKeyset = new KeysetPageImpl(firstRow, pageSize, lowest, highest);
        }

        PagedList<T> pagedResultList = new PagedListImpl<T>(result, newKeyset, totalSize, firstResult, pageSize);
        return pagedResultList;
    }

    private PagedList<T> getResultListViaIdQuery(long totalSize) {
        String idQueryString = getPageIdQueryString0();
        Query idQuery = em.createQuery(idQueryString)
                .setMaxResults(pageSize);

        if (keysetMode == KeysetMode.NONE) {
            idQuery.setFirstResult(firstRow);
        }

        parameterizeQuery(idQuery);
        List ids = idQuery.getResultList();

        if (ids.isEmpty()) {
            KeysetPage newKeysetPage = null;
            if (keysetMode == KeysetMode.NEXT) {
                // When we scroll over the last page to a non existing one, we reuse the current keyset
                newKeysetPage = keysetPage;
            }

            return new PagedListImpl<T>(newKeysetPage, totalSize, firstResult, pageSize);
        }

        Serializable[] lowest = null;
        Serializable[] highest = null;

        if (needsNewIdList) {
            if (keysetExtraction) {
                lowest = KeysetPaginationHelper.extractKey((Object[]) ids.get(0), 1);
                highest = KeysetPaginationHelper.extractKey((Object[]) ids.get(ids.size() - 1), 1);
            }

            List newIds = new ArrayList(ids.size());

            for (int i = 0; i < ids.size(); i++) {
                newIds.add(((Object[]) ids.get(i))[0]);
            }

            ids = newIds;
        }

        parameterManager.addParameterMapping(idParamName, ids);

        KeysetPage newKeyset = null;

        if (keysetExtraction) {
            newKeyset = new KeysetPageImpl(firstRow, pageSize, lowest, highest);
        }

        List<T> queryResultList = getQueryResultList();
        PagedList<T> pagedResultList = new PagedListImpl<T>(queryResultList, newKeyset, totalSize, firstResult, pageSize);
        return pagedResultList;
    }

    private List<T> getQueryResultList() {
        TypedQuery<T> query = (TypedQuery) em.createQuery(getQueryString0(), selectManager.getExpectedQueryResultType());
        if (selectManager.getSelectObjectBuilder() != null) {
            query = transformQuery(query);
        }

        parameterizeQuery(query);
        return query.getResultList();
    }

    private String getPageCountQueryString1() {
        StringBuilder sbSelectFrom = new StringBuilder();
        Metamodel m = em.getMetamodel();
        EntityType<?> entityType = fromClazz;
        String idName = entityType.getId(entityType.getIdType()
                .getJavaType())
                .getName();

        String idClause = new StringBuilder(joinManager.getRootAlias())
                .append('.')
                .append(idName)
                .toString();

        sbSelectFrom.append("SELECT COUNT(DISTINCT ").append(idClause).append(')');
        
        if (entityId != null) {
            parameterManager.addParameterMapping(ENTITY_PAGE_POSITION_PARAMETER_NAME, entityId);
            
            sbSelectFrom.append(", ");
            sbSelectFrom.append(jpaProvider.getCustomFunctionInvocation("PAGE_POSITION", 2));
            
            sbSelectFrom.append('(');
            appendSimplePageIdQueryString(sbSelectFrom);
            sbSelectFrom.append("),");
            
            sbSelectFrom.append(':').append(ENTITY_PAGE_POSITION_PARAMETER_NAME);
            sbSelectFrom.append(")");
        }
        
        sbSelectFrom.append(" FROM ")
                .append(fromClazz.getName())
                .append(' ')
                .append(joinManager.getRootAlias());
        joinManager.buildJoins(sbSelectFrom, EnumSet.of(ClauseType.ORDER_BY, ClauseType.SELECT), null);
        whereManager.buildClause(sbSelectFrom);

        return sbSelectFrom.toString();
    }
    
    private String appendSimplePageIdQueryString(StringBuilder sbSelectFrom) {
        queryGenerator.setAliasPrefix(PAGE_POSITION_ID_QUERY_ALIAS_PREFIX);
        
        String idName = joinManager.getRootId();
        StringBuilder idClause = new StringBuilder(PAGE_POSITION_ID_QUERY_ALIAS_PREFIX)
                .append(joinManager.getRootAlias())
                .append('.')
                .append(idName);

        sbSelectFrom.append("SELECT ")
                .append(idClause);
        
        sbSelectFrom.append(" FROM ")
                .append(fromClazz.getName())
                .append(' ')
                .append(PAGE_POSITION_ID_QUERY_ALIAS_PREFIX)
                .append(joinManager.getRootAlias());

        joinManager.buildJoins(sbSelectFrom, EnumSet.of(ClauseType.SELECT), PAGE_POSITION_ID_QUERY_ALIAS_PREFIX);
        whereManager.buildClause(sbSelectFrom);
        
        Set<String> clauses = new LinkedHashSet<String>();
        clauses.add(idClause.toString());
        clauses.addAll(orderByManager.buildGroupByClauses());
        groupByManager.buildGroupBy(sbSelectFrom, clauses);

        boolean inverseOrder = false;
        orderByManager.buildOrderBy(sbSelectFrom, inverseOrder, true);

        queryGenerator.setAliasPrefix(null);
        return sbSelectFrom.toString();
    }

    private String getPageIdQueryString1() {
        StringBuilder sbSelectFrom = new StringBuilder();
        String idName = joinManager.getRootId();
        StringBuilder idClause = new StringBuilder(joinManager.getRootAlias())
                .append('.')
                .append(idName);

        sbSelectFrom.append("SELECT ")
                .append(idClause);

        if (needsNewIdList) {
            orderByManager.buildSelectClauses(sbSelectFrom, keysetExtraction);
        }

        sbSelectFrom.append(" FROM ")
                .append(fromClazz.getName())
                .append(' ')
                .append(joinManager.getRootAlias());

        joinManager.buildJoins(sbSelectFrom, EnumSet.of(ClauseType.SELECT), null);

        if (keysetMode == KeysetMode.NONE) {
            whereManager.buildClause(sbSelectFrom);
        } else {
            sbSelectFrom.append(" WHERE ");

            keysetManager.buildKeysetPredicate(sbSelectFrom);

            if (whereManager.hasPredicates()) {
                sbSelectFrom.append(" AND ");
                whereManager.buildClausePredicate(sbSelectFrom);
            }
        }

        Set<String> clauses = new LinkedHashSet<String>();
        clauses.add(idClause.toString());
        clauses.addAll(orderByManager.buildGroupByClauses());
        groupByManager.buildGroupBy(sbSelectFrom, clauses);

        boolean inverseOrder = keysetMode == KeysetMode.PREVIOUS;
        orderByManager.buildOrderBy(sbSelectFrom, inverseOrder, true);

        // execute illegal collection access check
        orderByManager.acceptVisitor(new IllegalSubqueryDetector(aliasManager));

        return sbSelectFrom.toString();
    }

    private String getQueryString1() {
        StringBuilder sbSelectFrom = new StringBuilder();
        Metamodel m = em.getMetamodel();
        EntityType<?> entityType = fromClazz;
        String idName = entityType.getId(entityType.getIdType()
                .getJavaType())
                .getName();

        sbSelectFrom.append(selectManager.buildSelect(joinManager.getRootAlias()));
        sbSelectFrom.append(" FROM ")
                .append(fromClazz.getName())
                .append(' ')
                .append(joinManager.getRootAlias());

        /**
         * we have already selected the IDs so now we only need so select the
         * fields and apply the ordering all other clauses are not required any 
         * more and therefore we can also omit any joins which the SELECT or the 
         * ORDER_BY clause do not depend on
         */
        joinManager.buildJoins(sbSelectFrom, EnumSet.complementOf(EnumSet.of(ClauseType.SELECT, ClauseType.ORDER_BY)), null);
        sbSelectFrom.append(" WHERE ")
                .append(joinManager.getRootAlias())
                .append('.')
                .append(idName)
                .append(" IN :")
                .append(idParamName)
                .append("");

        Set<String> clauses = new LinkedHashSet<String>();
        clauses.addAll(groupByManager.buildGroupByClauses());
        if (selectManager.hasAggregateFunctions()) {
            clauses.addAll(selectManager.buildGroupByClauses(em.getMetamodel()));
            clauses.addAll(orderByManager.buildGroupByClauses());
        }
        groupByManager.buildGroupBy(sbSelectFrom, clauses);
        
        havingManager.buildClause(sbSelectFrom);
        queryGenerator.setResolveSelectAliases(false);
            orderByManager.buildOrderBy(sbSelectFrom, false, false);
        queryGenerator.setResolveSelectAliases(true);
        
        return sbSelectFrom.toString();
    }

    private String getObjectQueryString1() {
        StringBuilder sbSelectFrom = new StringBuilder();
        sbSelectFrom.append(selectManager.buildSelect(joinManager.getRootAlias()));

        if (keysetExtraction) {
            orderByManager.buildSelectClauses(sbSelectFrom, true);
        }

        sbSelectFrom.append(" FROM ")
                .append(fromClazz.getName())
                .append(' ')
                .append(joinManager.getRootAlias());

        joinManager.buildJoins(sbSelectFrom, EnumSet.noneOf(ClauseType.class), null);

        if (keysetMode == KeysetMode.NONE) {
            whereManager.buildClause(sbSelectFrom);
        } else {
            sbSelectFrom.append(" WHERE ");

            keysetManager.buildKeysetPredicate(sbSelectFrom);

            if (whereManager.hasPredicates()) {
                sbSelectFrom.append(" AND ");
                whereManager.buildClausePredicate(sbSelectFrom);
            }
        }

        Set<String> clauses = new LinkedHashSet<String>();
        clauses.addAll(groupByManager.buildGroupByClauses());
        if (selectManager.hasAggregateFunctions()) {
            clauses.addAll(selectManager.buildGroupByClauses(em.getMetamodel()));
            clauses.addAll(orderByManager.buildGroupByClauses());
        }
        groupByManager.buildGroupBy(sbSelectFrom, clauses);

        havingManager.buildClause(sbSelectFrom);

        boolean inverseOrder = keysetMode == KeysetMode.PREVIOUS;
        orderByManager.buildOrderBy(sbSelectFrom, inverseOrder, false);

        // execute illegal collection access check
        orderByManager.acceptVisitor(new IllegalSubqueryDetector(aliasManager));

        return sbSelectFrom.toString();
    }

    @Override
    public PaginatedCriteriaBuilder<T> from(Class<?> clazz) {
        return (PaginatedCriteriaBuilder<T>) super.from(clazz);
    }

    @Override
    public PaginatedCriteriaBuilder<T> from(Class<?> clazz, String alias) {
        return (PaginatedCriteriaBuilder<T>) super.from(clazz, alias);
    }

    @Override
    public PaginatedCriteriaBuilder<T> distinct() {
        throw new IllegalStateException("Calling distinct() on a PaginatedCriteriaBuilder is not allowed.");
    }

    @Override
    public PaginatedCriteriaBuilder<T> groupBy(String... paths) {
        throw new IllegalStateException("Calling groupBy() on a PaginatedCriteriaBuilder is not allowed.");
    }

    @Override
    public PaginatedCriteriaBuilder<T> groupBy(String expression) {
        throw new IllegalStateException("Calling groupBy() on a PaginatedCriteriaBuilder is not allowed.");
    }

    @Override
    public <Y> SelectObjectBuilder<PaginatedCriteriaBuilder<Y>> selectNew(Class<Y> clazz) {
        return (SelectObjectBuilder<PaginatedCriteriaBuilder<Y>>) super.selectNew(clazz);
    }

    @Override
    public <Y> PaginatedCriteriaBuilder<Y> selectNew(ObjectBuilder<Y> builder) {
        return (PaginatedCriteriaBuilder<Y>) super.selectNew(builder);
    }

    @Override
    public PaginatedCriteriaBuilder<T> select(String expression) {
        return (PaginatedCriteriaBuilder<T>) super.select(expression);
    }

    @Override
    public PaginatedCriteriaBuilder<T> select(String expression, String alias) {
        return (PaginatedCriteriaBuilder<T>) super.select(expression, alias);
    }

    @Override
    public CaseWhenBuilder<PaginatedCriteriaBuilder<T>> selectCase() {
        return (CaseWhenBuilder<PaginatedCriteriaBuilder<T>>) super.selectCase();
    }

    @Override
    public CaseWhenBuilder<PaginatedCriteriaBuilder<T>> selectCase(String alias) {
        return (CaseWhenBuilder<PaginatedCriteriaBuilder<T>>) super.selectCase(alias);
    }

    @Override
    public SimpleCaseWhenBuilder<PaginatedCriteriaBuilder<T>> selectSimpleCase(String expression) {
        return (SimpleCaseWhenBuilder<PaginatedCriteriaBuilder<T>>) super.selectSimpleCase(expression);
    }

    @Override
    public SimpleCaseWhenBuilder<PaginatedCriteriaBuilder<T>> selectSimpleCase(String expression, String alias) {
        return (SimpleCaseWhenBuilder<PaginatedCriteriaBuilder<T>>) super.selectSimpleCase(expression, alias);
    }

    @Override
    public SubqueryInitiator<PaginatedCriteriaBuilder<T>> selectSubquery() {
        return (SubqueryInitiator<PaginatedCriteriaBuilder<T>>) super.selectSubquery();
    }

    @Override
    public SubqueryInitiator<PaginatedCriteriaBuilder<T>> selectSubquery(String alias) {
        return (SubqueryInitiator<PaginatedCriteriaBuilder<T>>) super.selectSubquery(alias);
    }

    @Override
    public SubqueryInitiator<PaginatedCriteriaBuilder<T>> selectSubquery(String subqueryAlias, String expression) {
        return (SubqueryInitiator<PaginatedCriteriaBuilder<T>>) super.selectSubquery(subqueryAlias, expression);
    }

    @Override
    public SubqueryInitiator<PaginatedCriteriaBuilder<T>> selectSubquery(String subqueryAlias, String expression, String selectAlias) {
        return (SubqueryInitiator<PaginatedCriteriaBuilder<T>>) super.selectSubquery(subqueryAlias, expression, selectAlias);
    }
}
