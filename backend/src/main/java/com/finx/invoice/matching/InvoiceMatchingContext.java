package com.finx.invoice.matching;

import com.finx.deal.entity.Deal;
import com.finx.invoice.entity.Invoice;
import com.finx.milestone.entity.Deliverable;
import com.finx.milestone.entity.Milestone;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationBankAccount;
import com.finx.verification.entity.KybProfile;

import java.util.List;

public record InvoiceMatchingContext(
        Invoice invoice,
        Deal deal,
        Milestone milestone,
        Organization vendorOrg,
        Organization buyerOrg,
        KybProfile vendorKyb,
        OrganizationBankAccount vendorBank,
        List<Deliverable> deliverables,
        List<Invoice> existingInvoices,
        InvoiceMatchingPolicy policy
) {
}
