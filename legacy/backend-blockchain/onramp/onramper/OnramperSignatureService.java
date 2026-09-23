package com.finx.payment.service.onramp.onramper;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class OnramperSignatureService {
    @org.springframework.beans.factory.annotation.Value("${ONRAMP_WIDGET_PUBLIC_KEY:}")
    private String widgetKey;
    @org.springframework.beans.factory.annotation.Value("${ONRAMP_WIDGET_URL:}")
    private String widgetUrl;
    @org.springframework.beans.factory.annotation.Value("${ONRAMP_ASSET_ID:}")
    private String assetId;
    private static final Logger log = LoggerFactory.getLogger(OnramperSignatureService.class);
    
    private final OnramperProperties properties;

    public OnramperSignatureService(OnramperProperties properties) {
        this.properties = properties;
    }

    public void validateSettings() {
        if (properties.getV2PrivateKey() == null || properties.getV2PrivateKey().isBlank()) {
            throw new RuntimeException("ONRAMP_V2_PRIVATE_KEY missing. V2 Intent blocked.");
        }
    }

    public String hashEndUserIp(String endUserIp) {
        validateSettings(); // Fail closed on missing V2 key
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(endUserIp.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) { hexString.append('0'); }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("Error hashing IP", e);
            throw new RuntimeException("Failed to hash end-user IP", e);
        }
    }

    public boolean widgetConfigured() {
        return widgetKey != null && widgetKey.startsWith("pk_test_")
                && "https://buy.onramper.dev".equals(widgetUrl) && assetId != null && !assetId.isBlank()
                && properties.getV2PrivateKey() != null && !properties.getV2PrivateKey().isBlank();
    }

    /** Provider sandbox UI only; sandbox callbacks are never token settlement. */
    public String walletFundingWidget(java.util.UUID intent, String wallet) {
        if (!widgetConfigured()) throw new com.finx.exception.BadRequestException("Onramper sandbox widget is not configured");
        var fields = new java.util.TreeMap<String,String>();
        fields.put("apiKey",widgetKey);
        fields.put("wallets",assetId+":"+wallet);
        fields.put("defaultCrypto",assetId);
        fields.put("onlyCryptos",assetId);
        fields.put("onlyOnramps","banxa");
        fields.put("isAddressEditable","false");
        fields.put("partnerContext",intent.toString());
        try {
            String timestamp = new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(java.time.Instant.now());
            String nonce = java.util.UUID.randomUUID().toString();
            String query = fields.entrySet().stream().map(e -> encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
            String emptyHash=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
            String canonical=String.join("\n","ONRAMPER-SIG-V2",timestamp,nonce,"GET","/",query,"",emptyHash);
            String pem=properties.getV2PrivateKey().replace("\\n","\n").replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s","");
            var key=java.security.KeyFactory.getInstance("Ed25519").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(pem)));
            var signature=java.security.Signature.getInstance("Ed25519");signature.initSign(key);signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return widgetUrl+"/?"+query+"&sigV2="+encode("v2:"+java.util.Base64.getEncoder().encodeToString(signature.sign()))
                    +"&sigV2Timestamp="+encode(timestamp)+"&sigV2Nonce="+nonce+"&sigV2Fields="+encode(String.join(",",fields.keySet()))
                    +"&sigV2Expiry="+encode(java.time.Instant.now().plusSeconds(900).toString());
        } catch(Exception e) { throw new com.finx.exception.BadRequestException("Provider signing configuration is invalid"); }
    }
    private static String encode(String value) { return java.net.URLEncoder.encode(value,StandardCharsets.UTF_8); }
}
