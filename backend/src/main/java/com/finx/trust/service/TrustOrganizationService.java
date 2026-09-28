package com.finx.trust.service;

import com.finx.trust.entity.*;
import com.finx.trust.repository.*;
import com.finx.user.entity.User;
import com.finx.common.enums.Role;
import com.finx.security.service.UserPrincipal;
import com.finx.exception.ResourceNotFoundException;
import jakarta.persistence.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class TrustOrganizationService {
    private final TrustOrganizationRepository organizations;
    private final TrustMembershipRepository members;
    private final EntityManager em;
    public TrustOrganizationService(TrustOrganizationRepository organizations, TrustMembershipRepository members, EntityManager em) {
        this.organizations=organizations; this.members=members; this.em=em;
    }
    @Transactional
    public UUID ensureForUser(UUID userId) {
        var existing=members.findById(userId);
        if(existing.isPresent()) return existing.get().organizationId;
        User user=em.find(User.class,userId,LockModeType.PESSIMISTIC_WRITE);
        if(user==null || (user.getRole()!=Role.BUYER && user.getRole()!=Role.SELLER))
            throw new IllegalArgumentException("Only buyer/vendor accounts have trust organizations");
        existing=members.findById(userId);
        if(existing.isPresent()) return existing.get().organizationId;
        var organization=new TrustOrganization(); organization.id=userId; organization.name=user.getName();
        organization.createdAt=user.getCreatedAt(); organization.dirty=true;
        organizations.saveAndFlush(organization);
        var member=new TrustMembership(); member.userId=userId; member.organizationId=organization.id;
        members.saveAndFlush(member);
        return organization.id;
    }
    @Transactional(readOnly=true)
    public void requireAccess(UUID id, UserPrincipal principal) {
        if(principal==null) throw new AccessDeniedException("Authentication required");
        if(principal.getRole()!=Role.ADMIN && !members.findById(principal.getId()).map(m->m.organizationId.equals(id)).orElse(false))
            throw new AccessDeniedException("Organization membership required");
        if(!organizations.existsById(id)) throw new ResourceNotFoundException("Organization","id",id);
    }
}
