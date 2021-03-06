package com.softwareverde.bitcoin.transaction;

import com.softwareverde.bitcoin.hash.sha256.MutableSha256Hash;
import com.softwareverde.bitcoin.hash.sha256.Sha256Hash;
import com.softwareverde.bitcoin.util.BitcoinUtil;
import com.softwareverde.bitcoin.util.ByteUtil;
import com.softwareverde.util.bytearray.ByteArrayBuilder;

public class TransactionHasher {
    public Sha256Hash hashTransaction(final Transaction transaction) {
        final TransactionDeflater transactionDeflater = new TransactionDeflater();
        final ByteArrayBuilder byteArrayBuilder = transactionDeflater.toByteArrayBuilder(transaction);
        final byte[] doubleSha256 = BitcoinUtil.sha256(BitcoinUtil.sha256(byteArrayBuilder.build()));
        return MutableSha256Hash.wrap(ByteUtil.reverseEndian(doubleSha256));
    }
}
