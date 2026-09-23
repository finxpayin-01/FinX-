package com.finx.blockchain;

import com.finx.payment.service.onramp.onramper.*;
import com.finx.payment.service.onramp.OnRampWebhookInbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.h2.jdbcx.JdbcDataSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderBoundaryTest {
    @Test void widgetSignatureBindsVerifiedWalletAndOnlyExposesPublishableKey() throws Exception {
        var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var properties=new OnramperProperties();
        properties.setV2PrivateKey("-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----");
        var signer=new OnramperSignatureService(properties);
        ReflectionTestUtils.setField(signer,"widgetKey","pk_test_fixture");
        ReflectionTestUtils.setField(signer,"widgetUrl","https://buy.onramper.dev");
        ReflectionTestUtils.setField(signer,"assetId","test_asset");
        String wallet="0x"+"1".repeat(40);
        String url=signer.walletFundingWidget(UUID.randomUUID(),wallet);
        var fields=new TreeMap<String,String>();
        for(String item:new URI(url).getRawQuery().split("&")) {
            String[] entry=item.split("=",2);fields.put(URLDecoder.decode(entry[0],StandardCharsets.UTF_8),URLDecoder.decode(entry[1],StandardCharsets.UTF_8));
        }
        assertEquals("test_asset:"+wallet,fields.get("wallets"));
        assertFalse(url.contains("PRIVATE"));
        String signature=fields.remove("sigV2"),timestamp=fields.remove("sigV2Timestamp"),nonce=fields.remove("sigV2Nonce");
        fields.remove("sigV2Expiry");fields.remove("sigV2Fields");
        String query=fields.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        String emptyHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
        var verifier=Signature.getInstance("Ed25519");verifier.initVerify(pair.getPublic());
        verifier.update(String.join("\n","ONRAMPER-SIG-V2",timestamp,nonce,"GET","/",query,"",emptyHash).getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(signature.substring(3))));
        ReflectionTestUtils.setField(signer,"widgetKey","secret_api_key");
        assertFalse(signer.widgetConfigured());
        assertThrows(RuntimeException.class,()->signer.walletFundingWidget(UUID.randomUUID(),wallet));
    }
    @Test void signedWebhookIsAnIdempotentSignalNeverSettlement() throws Exception {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        var jdbc=new JdbcTemplate(source);
        jdbc.execute("create table webhook_events(id uuid primary key,provider varchar(100),provider_event_id varchar(255),event_type varchar(100),payload_hash varchar(255),processing_status varchar(50),received_at timestamp,unique(provider,provider_event_id))");
        var props=new OnramperProperties();props.setWebhookSecret(UUID.randomUUID().toString());
        var inbox=new OnRampWebhookInbox(jdbc,new ObjectMapper(),new OnramperWebhookVerifier(),props);
        String body="{\"id\":\"fixture-event\",\"type\":\"transaction_completed\"}";
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(props.getWebhookSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        inbox.receive(body,signature);inbox.receive(body,signature);
        assertEquals(1L,jdbc.queryForObject("select count(*) from webhook_events",Long.class));
        assertEquals("SIGNAL_ONLY",jdbc.queryForObject("select processing_status from webhook_events",String.class));
        assertThrows(RuntimeException.class,()->inbox.receive(body,"forged"));
    }
}
