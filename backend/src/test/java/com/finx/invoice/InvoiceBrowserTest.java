package com.finx.invoice;

import com.finx.common.enums.*;
import com.finx.deal.entity.*;
import com.finx.milestone.entity.*;
import com.finx.org.entity.*;
import com.finx.org.enums.OrgEnums.*;
import com.finx.verification.entity.*;
import com.finx.verification.enums.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real browser flow against isolated H2, real HTTP and the normal JWT filter chain. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT, properties={
        "server.port=8087", "cors.allowed-origins=http://localhost:3108", "jwt.access-token-expiration-ms=3600000",
        "spring.datasource.url=jdbc:h2:mem:invoice_browser;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "logging.level.org.hibernate.SQL=WARN", "logging.level.org.springframework=WARN"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="FINX_INVOICE_BROWSER", matches="true")
class InvoiceBrowserTest {
    @Autowired com.finx.user.repository.UserRepository users;
    @Autowired com.finx.org.repository.OrganizationRepository orgs;
    @Autowired com.finx.org.repository.OrganizationMemberRepository members;
    @Autowired com.finx.verification.repository.KybProfileRepository kybs;
    @Autowired com.finx.verification.repository.KycProfileRepository kycs;
    @Autowired com.finx.verification.repository.VerificationConsentRepository consents;
    @Autowired com.finx.org.repository.OrganizationBankAccountRepository banks;
    @Autowired com.finx.deal.repository.DealRepository deals;
    @Autowired com.finx.milestone.repository.MilestoneRepository milestones;
    @Autowired com.finx.milestone.repository.DeliverableRepository deliverables;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

    @Test void browserFlow() throws Exception {
        Map<String,Object> fixture=new TransactionTemplate(transactions).execute(tx -> {
            var vendor=user("vendor",Role.SELLER); var buyer=user("buyer",Role.BUYER); var pm=user("pm",Role.PROJECT_MANAGER);
            user("other-vendor",Role.SELLER); user("admin",Role.ADMIN);
            var vendorOrg=org(vendor,"Apex Engineering Limited","ABCDE1234F","27ABCDE1234F1Z5");
            var buyerOrg=org(buyer,"Horizon Manufacturing Limited","BBBBB1234F","27BBBBB1234F1Z5");
            var deal=new Deal("Invoice browser acceptance project","Isolated synthetic fixture",buyer.getId(),vendor.getId(),new BigDecimal("750000"),"INR",DealStatus.IN_PROGRESS);
            deal.setSellerOrganizationId(vendorOrg.getId()); deal.setBuyerOrganizationId(buyerOrg.getId()); deal.setProjectManagerId(pm.getId()); deals.saveAndFlush(deal);
            List<String> ids=new ArrayList<>();
            for(int k=1;k<=3;k++) {
                var milestone=new Milestone(deal.getId(),"Invoice milestone "+k,"Manufacturing acceptance evidence",k,new BigDecimal("250000"),"INR",Instant.now().plusSeconds(864000));
                milestone.setStatus(MilestoneStatus.IN_PROGRESS); milestone.setInvoiceRequired(true); milestones.saveAndFlush(milestone); ids.add(milestone.getId().toString());
                var evidence=new Deliverable(milestone.getId(),vendor.getId(),"completion-certificate.pdf","private:synthetic-fixture","Completion certificate inspected in fixture");
                evidence.setStatus(DeliverableStatus.APPROVED); deliverables.save(evidence);
            }
            return Map.of("dealId",deal.getId().toString(),"milestoneIds",ids,"vendorName",vendorOrg.getLegalName(),"buyerName",buyerOrg.getLegalName(),"vendorPan",vendorOrg.getTaxId(),"vendorGstin",vendorOrg.getGstin(),"buyerGstin",buyerOrg.getGstin());
        });
        Path directory=Path.of("../test-results/invoice-browser"); Files.createDirectories(directory);
        Path fixtureFile=directory.resolve("fixture.json"); json.writeValue(fixtureFile.toFile(),fixture);
        Process process=new ProcessBuilder("node","../scripts/e2e-invoice-verification.mjs",fixtureFile.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("browser.log").toFile()).start();
        boolean finished=process.waitFor(8,java.util.concurrent.TimeUnit.MINUTES);
        if(!finished) process.destroyForcibly();
        assertTrue(finished,"Browser timed out; see test-results/invoice-browser/browser.log");
        assertEquals(0,process.exitValue(),"Browser flow failed; see test-results/invoice-browser/browser.log");
    }
    private com.finx.user.entity.User user(String name,Role role) {
        var u=new com.finx.user.entity.User(); u.setEmail(name+"@invoice-fixture.local"); u.setName(name); u.setRole(role); u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(encoder.encode("InvoiceFixture123!")); users.saveAndFlush(u);
        var kyc=new KycProfile(u.getId(),name,"******234F"); kyc.setStatus(VerificationStatus.VERIFIED); kyc.setPanStatus(VerificationStatus.VERIFIED); kyc.setIdentityStatus(VerificationStatus.VERIFIED); kycs.save(kyc);
        for(var type:List.of(ConsentType.PAN,ConsentType.IDENTITY)) consents.save(new VerificationConsent(u.getId(),null,type,"1.0","Synthetic invoice browser fixture consent","127.0.0.1","JUnit fixture"));
        return u;
    }
    private Organization org(com.finx.user.entity.User owner,String name,String pan,String gst) {
        var org=new Organization(name,name,EntityType.PRIVATE_LIMITED,"REG-FIXTURE",pan,gst,"Mumbai",owner.getId()); org.setVerificationStatus(OrgVerificationStatus.VERIFIED); orgs.saveAndFlush(org);
        members.save(new OrganizationMember(org.getId(),owner.getId(),OrgRole.OWNER));
        var kyb=new KybProfile(org.getId(),"******234F",gst,null,null); kyb.setStatus(VerificationStatus.VERIFIED); kyb.setPanStatus(VerificationStatus.VERIFIED); kyb.setGstStatus(VerificationStatus.VERIFIED); kyb.setRegistrationStatus(VerificationStatus.VERIFIED); kybs.save(kyb);
        for(var type:List.of(ConsentType.PAN,ConsentType.GST,ConsentType.BANK)) consents.save(new VerificationConsent(owner.getId(),org.getId(),type,"1.0","Synthetic invoice browser fixture consent","127.0.0.1","JUnit fixture"));
        var bank=new OrganizationBankAccount(org.getId(),name,"******1234","fixture-bank-hash-"+org.getId(),"HDFC0001234","HDFC",null,true); bank.setVerificationStatus(BankVerificationStatus.VERIFIED); banks.save(bank);
        return org;
    }
}
