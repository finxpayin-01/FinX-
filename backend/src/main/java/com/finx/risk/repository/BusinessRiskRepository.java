package com.finx.risk.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.exception.ResourceNotFoundException;
import com.finx.risk.dto.RiskDtos.*;
import com.finx.risk.entity.*;
import jakarta.persistence.*;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class BusinessRiskRepository {
    @PersistenceContext private EntityManager em;
    private final ObjectMapper json;
    public BusinessRiskRepository(ObjectMapper json) { this.json = json; }
    public <T> T persist(T value) { em.persist(value); return value; }
    public void flush() { em.flush(); }
    public <T> T get(Class<T> type, UUID id) {
        T value = em.find(type,id);
        if (value == null) throw new ResourceNotFoundException(type.getSimpleName(),"id",id);
        return value;
    }
    public <T> T lock(Class<T> type, UUID id) {
        em.flush();
        T value = get(type,id);
        em.refresh(value,LockModeType.PESSIMISTIC_WRITE);
        return value;
    }
    public <T> List<T> query(Class<T> type, String jpql, Map<String,?> params, int limit) {
        TypedQuery<T> query = em.createQuery(jpql,type).setMaxResults(limit);
        params.forEach(query::setParameter);
        return query.getResultList();
    }
    public long count(String jpql, Map<String,?> params) {
        TypedQuery<Long> query = em.createQuery(jpql,Long.class);
        params.forEach(query::setParameter);
        return query.getSingleResult();
    }
    public <T> List<T> page(Class<T> type,String jpql,Map<String,?> params,int page,int size) {
        TypedQuery<T> query=em.createQuery(jpql,type).setFirstResult(page*size).setMaxResults(size);
        params.forEach(query::setParameter);
        return query.getResultList();
    }
    public String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Risk serialization failed",e); }
    }
    public <T> T read(String value, Class<T> type) {
        try { return json.readValue(value,type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Risk snapshot cannot be read",e); }
    }
    public Assessment current(RiskScore score) {
        if (score.assessmentId == null) return null;
        var rows = query(RiskScoreHistory.class,"from RiskScoreHistory where assessmentId=:id",Map.of("id",score.assessmentId),1);
        return rows.isEmpty() ? null : read(rows.getFirst().snapshot,Assessment.class);
    }
}
