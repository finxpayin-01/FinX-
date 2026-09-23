package com.finx.payment.service.onramp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.exception.BadRequestException;
import com.finx.payment.service.onramp.onramper.OnramperProperties;
import com.finx.payment.service.onramp.onramper.OnramperWebhookVerifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class OnRampWebhookInbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final OnramperWebhookVerifier verifier;
    private final OnramperProperties properties;
    public OnRampWebhookInbox(JdbcTemplate jdbc,ObjectMapper mapper,OnramperWebhookVerifier verifier,OnramperProperties properties) {
        this.jdbc=jdbc;this.mapper=mapper;this.verifier=verifier;this.properties=properties;
    }
    @Transactional
    public void receive(String body,String signature) {
        if (body.length()>65536 || !verifier.verifySignature(body,signature,properties.getWebhookSecret()))
            throw new BadRequestException("Webhook signature or size invalid");
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
            var json=mapper.readTree(body);
            String id=json.path("id").asText(hash),type=json.path("type").asText("unknown");
            if(id.isBlank()||id.length()>255||type.length()>100)throw new BadRequestException("Invalid webhook identifiers");
            var prior=jdbc.queryForList("select payload_hash from webhook_events where provider='onramper' and provider_event_id=?",id);
            if(!prior.isEmpty()) {
                if(!hash.equals(prior.get(0).get("payload_hash")))throw new BadRequestException("Webhook ID reused with different content");
                return;
            }
            jdbc.update("insert into webhook_events(id,provider,provider_event_id,event_type,payload_hash,processing_status,received_at) values(?,'onramper',?,?,?,'SIGNAL_ONLY',CURRENT_TIMESTAMP)",UUID.randomUUID(),id,type,hash);
        } catch(BadRequestException | org.springframework.dao.DataAccessException e) {throw e;}
        catch(Exception e) {throw new BadRequestException("Invalid webhook payload");}
    }
}
