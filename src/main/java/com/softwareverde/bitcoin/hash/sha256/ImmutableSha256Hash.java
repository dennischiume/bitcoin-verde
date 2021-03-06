package com.softwareverde.bitcoin.hash.sha256;

import com.softwareverde.bitcoin.hash.ImmutableHash;
import com.softwareverde.bitcoin.util.ByteUtil;
import com.softwareverde.constable.Const;

public class ImmutableSha256Hash extends ImmutableHash implements Sha256Hash, Const {
    protected ImmutableSha256Hash(final byte[] bytes) {
        super(new byte[BYTE_COUNT]);
        ByteUtil.setBytes(_bytes, bytes);
    }

    public ImmutableSha256Hash() {
        super(new byte[BYTE_COUNT]);
    }

    public ImmutableSha256Hash(final Sha256Hash hash) {
        super(hash);
    }

    @Override
    public Sha256Hash toReversedEndian() {
        return MutableSha256Hash.wrap(ByteUtil.reverseEndian(_bytes));
    }

    @Override
    public ImmutableSha256Hash asConst() {
        return this;
    }
}
