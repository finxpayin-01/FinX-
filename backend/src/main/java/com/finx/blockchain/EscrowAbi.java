package com.finx.blockchain;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import java.math.*;
import java.util.*;
import com.finx.exception.BadRequestException;

public final class EscrowAbi {
    private EscrowAbi() {}
    public static Bytes32 id(UUID id) { return new Bytes32(Numeric.hexStringToByteArray(Hash.sha3String("finx:" + id))); }
    public static String encode(String name, Type... args) {
        return FunctionEncoder.encode(new Function(name, List.of(args), List.of()));
    }
    public static BigInteger atomic(String amount, int decimals) {
        try {
            BigInteger units = new BigDecimal(amount).movePointRight(decimals).toBigIntegerExact();
            if (units.signum() <= 0 || units.bitLength() > 256) throw new ArithmeticException();
            return units;
        } catch (RuntimeException e) { throw new BadRequestException("Token amount must be positive, exact and fit uint256."); }
    }
    public static String create(Map<String, Object> e, List<Map<String, Object>> allocations) {
        return encode("createEscrow", id(ChainStore.uuid(e,"id")), id(ChainStore.uuid(e,"deal_id")),
                new Address(ChainStore.str(e,"buyer_address")), new Address(ChainStore.str(e,"vendor_address")),
                new Address(ChainStore.str(e,"token_address")),
                new DynamicArray<>(Bytes32.class, allocations.stream().map(a -> id(ChainStore.uuid(a,"milestone_id"))).toList()),
                new DynamicArray<>(Uint256.class, allocations.stream().map(a -> new Uint256(ChainStore.units(a,"amount_atomic"))).toList()));
    }
    public static BigInteger word(String hex, int index) {
        String raw = Numeric.cleanHexPrefix(hex);
        if (raw.length() < (index + 1) * 64) throw new IllegalStateException("Malformed chain response");
        return new BigInteger(raw.substring(index * 64, (index + 1) * 64), 16);
    }
    public static String addressWord(String hex, int index) {
        return "0x" + Numeric.toHexStringNoPrefixZeroPadded(word(hex,index),64).substring(24);
    }
}
