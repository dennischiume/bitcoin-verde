package com.softwareverde.network.p2p.node.manager;

import com.softwareverde.bitcoin.server.message.BitcoinProtocolMessage;
import com.softwareverde.concurrent.pool.ThreadPool;
import com.softwareverde.constable.bytearray.ByteArray;
import com.softwareverde.constable.bytearray.MutableByteArray;
import com.softwareverde.network.p2p.message.ProtocolMessage;
import com.softwareverde.network.p2p.message.type.*;
import com.softwareverde.network.p2p.node.Node;
import com.softwareverde.network.p2p.node.address.NodeIpAddress;
import com.softwareverde.util.type.time.SystemTime;

public class FakeNode extends Node {
    protected static long _nextNonce = 0L;

    protected Long _lastMessageReceivedTimestamp = 0L;

    public FakeNode(final String host, final ThreadPool threadPool) {
        super(host, 0, BitcoinProtocolMessage.BINARY_PACKET_FORMAT, threadPool);
    }

    public FakeNode(final String host, final SystemTime systemTime, final ThreadPool threadPool) {
        super(host, 0, BitcoinProtocolMessage.BINARY_PACKET_FORMAT, systemTime, threadPool);
    }

    @Override
    protected PingMessage _createPingMessage() {
        return new PingMessage() {
            @Override
            public Long getNonce() {
                return 0L;
            }

            @Override
            public ByteArray getBytes() {
                return new MutableByteArray(0);
            }
        };
    }

    @Override
    protected PongMessage _createPongMessage(final PingMessage pingMessage) {
        return new PongMessage() {
            @Override
            public Long getNonce() {
                return 0L;
            }

            @Override
            public ByteArray getBytes() {
                return new MutableByteArray(0);
            }
        };
    }

    @Override
    protected SynchronizeVersionMessage _createSynchronizeVersionMessage() {
        return new SynchronizeVersionMessage() {
            @Override
            public Long getNonce() {
                return _nextNonce++;
            }

            @Override
            public NodeIpAddress getLocalNodeIpAddress() {
                return null;
            }

            @Override
            public Long getTimestamp() {
                return 0L;
            }

            @Override
            public String getUserAgent() {
                return "TestAgent";
            }

            @Override
            public ByteArray getBytes() {
                return new MutableByteArray(0);
            }
        };
    }

    @Override
    public void connect() { }

    @Override
    protected AcknowledgeVersionMessage _createAcknowledgeVersionMessage(final SynchronizeVersionMessage synchronizeVersionMessage) { return null; }

    @Override
    protected NodeIpAddressMessage _createNodeIpAddressMessage() { return null; }

    @Override
    public Boolean hasActiveConnection() { return true; }

    @Override
    public Long getLastMessageReceivedTimestamp() {
        return _lastMessageReceivedTimestamp;
    }

    @Override
    public Boolean handshakeIsComplete() {
        return true;
    }

    @Override
    protected void _queueMessage(final ProtocolMessage message) {
        super._queueMessage(message);
    }

    public void triggerConnected() {
        _onConnect();
    }

    public void triggerHandshakeComplete() {
        _onAcknowledgeVersionMessageReceived(null);
    }

    public void respondWithPong() {
        _onPongReceived(new PongMessage() {
            @Override
            public Long getNonce() {
                return 0L;
            }

            @Override
            public ByteArray getBytes() {
                return new MutableByteArray(0);
            }
        });
    }
}