package com.finx.blockchain;

import com.finx.exception.BadRequestException;
import com.finx.security.service.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;

@Service
public class WalletService {
    private final ChainStore store;
    private final ChainConfig config;
    public WalletService(ChainStore store, ChainConfig config) { this.store = store; this.config = config; }

    @Transactional
    public Map<String,Object> challenge(String address, UserPrincipal actor) {
        config.requireConfigured();
        if (!ChainConfig.address(address)) throw new BadRequestException("Invalid wallet address");
        store.one("select id from users where id = ? for update", actor.getId());
        var rows = store.jdbc().queryForList("select * from blockchain_wallets where user_id = ? and chain_id = ?", actor.getId(),config.chainId);
        if (!rows.isEmpty() && Boolean.TRUE.equals(rows.get(0).get("verified"))) {
            if (!ChainStore.str(rows.get(0),"address").equalsIgnoreCase(address))
                throw new BadRequestException("Wallet is already bound. Wallet rotation requires a separate recovery process.");
            return Map.of("verified",true,"address",address.toLowerCase(Locale.ROOT));
        }
        String message = "FinX wallet ownership\nDomain: " + config.walletDomain + "\nUser: " + actor.getId()
                + "\nChain: " + config.chainId + "\nAddress: " + address.toLowerCase(Locale.ROOT) + "\nNonce: " + UUID.randomUUID();
        store.jdbc().update("delete from blockchain_wallets where user_id = ? and chain_id = ? and verified = false",actor.getId(),config.chainId);
        store.jdbc().update("insert into blockchain_wallets(user_id,chain_id,address,challenge,expires_at,verified) values(?,?,?,?,?,false)",
                actor.getId(),config.chainId,address.toLowerCase(Locale.ROOT),message,Timestamp.from(Instant.now().plusSeconds(300)));
        return Map.of("verified",false,"message",message);
    }

    @Transactional
    public Map<String,Object> verify(String signature, UserPrincipal actor) {
        var wallet = store.one("select * from blockchain_wallets where user_id = ? and chain_id = ? for update",actor.getId(),config.chainId);
        if (Boolean.TRUE.equals(wallet.get("verified"))) return Map.of("address",wallet.get("address"),"verified",true);
        Instant expires = store.jdbc().queryForObject("select expires_at from blockchain_wallets where user_id = ? and chain_id = ?",
                (rs,n) -> rs.getTimestamp(1).toInstant(),actor.getId(),config.chainId);
        if (expires == null || expires.isBefore(Instant.now()) || signature == null || !signature.matches("0x[0-9a-fA-F]{130}"))
            throw new BadRequestException("Wallet challenge expired or signature malformed");
        try {
            byte[] bytes = Numeric.hexStringToByteArray(signature);
            byte v = bytes[64];
            if (v < 27) v += 27;
            var data = new Sign.SignatureData(v,Arrays.copyOfRange(bytes,0,32),Arrays.copyOfRange(bytes,32,64));
            String recovered = "0x" + Keys.getAddress(Sign.signedPrefixedMessageToKey(ChainStore.str(wallet,"challenge").getBytes(StandardCharsets.UTF_8),data));
            if (!recovered.equalsIgnoreCase(ChainStore.str(wallet,"address"))) throw new IllegalArgumentException();
        } catch (Exception e) { throw new BadRequestException("Wallet signature does not match"); }
        store.jdbc().update("update blockchain_wallets set verified = true where user_id = ? and chain_id = ?",actor.getId(),config.chainId);
        return Map.of("address",wallet.get("address"),"verified",true);
    }
    public String address(UUID user) {
        var rows = store.jdbc().queryForList("select address from blockchain_wallets where user_id = ? and chain_id = ? and verified = true",user,config.chainId);
        if (rows.isEmpty()) throw new BadRequestException("Both parties must verify a wallet before creating escrow");
        return ChainStore.str(rows.get(0),"address");
    }
}
