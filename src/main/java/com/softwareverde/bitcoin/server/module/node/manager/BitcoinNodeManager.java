package com.softwareverde.bitcoin.server.module.node.manager;

import com.softwareverde.bitcoin.block.Block;
import com.softwareverde.bitcoin.block.BlockId;
import com.softwareverde.bitcoin.block.header.BlockHeader;
import com.softwareverde.bitcoin.block.header.BlockHeaderWithTransactionCount;
import com.softwareverde.bitcoin.block.header.ImmutableBlockHeaderWithTransactionCount;
import com.softwareverde.bitcoin.block.thin.AssembleThinBlockResult;
import com.softwareverde.bitcoin.block.thin.ThinBlockAssembler;
import com.softwareverde.bitcoin.hash.sha256.Sha256Hash;
import com.softwareverde.bitcoin.server.SynchronizationStatus;
import com.softwareverde.bitcoin.server.database.BlockDatabaseManager;
import com.softwareverde.bitcoin.server.database.BlockHeaderDatabaseManager;
import com.softwareverde.bitcoin.server.database.PendingBlockDatabaseManager;
import com.softwareverde.bitcoin.server.database.cache.DatabaseManagerCache;
import com.softwareverde.bitcoin.server.message.type.node.address.BitcoinNodeIpAddress;
import com.softwareverde.bitcoin.server.message.type.node.feature.LocalNodeFeatures;
import com.softwareverde.bitcoin.server.message.type.node.feature.NodeFeatures;
import com.softwareverde.bitcoin.server.message.type.query.block.QueryBlocksMessage;
import com.softwareverde.bitcoin.server.module.node.BitcoinNodeFactory;
import com.softwareverde.bitcoin.server.module.node.MemoryPoolEnquirer;
import com.softwareverde.bitcoin.server.module.node.sync.BlockFinderHashesBuilder;
import com.softwareverde.bitcoin.server.node.BitcoinNode;
import com.softwareverde.bitcoin.transaction.Transaction;
import com.softwareverde.bloomfilter.BloomFilter;
import com.softwareverde.concurrent.pool.ThreadPool;
import com.softwareverde.concurrent.pool.ThreadPoolFactory;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.mutable.MutableList;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.database.mysql.MysqlDatabaseConnection;
import com.softwareverde.database.mysql.MysqlDatabaseConnectionFactory;
import com.softwareverde.io.Logger;
import com.softwareverde.network.ip.Ip;
import com.softwareverde.network.p2p.node.NodeId;
import com.softwareverde.network.p2p.node.manager.NodeManager;
import com.softwareverde.network.time.MutableNetworkTime;
import com.softwareverde.util.Tuple;
import com.softwareverde.util.Util;

public class BitcoinNodeManager extends NodeManager<BitcoinNode> {
    public static final Integer MINIMUM_THIN_BLOCK_TRANSACTION_COUNT = 64;

    public static class BanCriteria {
        public static final Integer FAILED_CONNECTION_ATTEMPT_COUNT = 3;
    }

    public interface FailableCallback {
        default void onFailure() { }
    }
    public interface BlockInventoryMessageCallback extends BitcoinNode.BlockInventoryMessageCallback, FailableCallback { }
    public interface DownloadBlockCallback extends BitcoinNode.DownloadBlockCallback {
        default void onFailure(Sha256Hash blockHash) { }
    }
    public interface DownloadBlockHeadersCallback extends BitcoinNode.DownloadBlockHeadersCallback, FailableCallback { }
    public interface DownloadTransactionCallback extends BitcoinNode.DownloadTransactionCallback {
        default void onFailure(List<Sha256Hash> transactionHashes) { }
    }

    protected final MysqlDatabaseConnectionFactory _databaseConnectionFactory;
    protected final DatabaseManagerCache _databaseManagerCache;
    protected final NodeInitializer _nodeInitializer;
    protected final BanFilter _banFilter;
    protected final MemoryPoolEnquirer _memoryPoolEnquirer;
    protected final SynchronizationStatus _synchronizationStatusHandler;
    protected final ThreadPoolFactory _threadPoolFactory;
    protected final LocalNodeFeatures _localNodeFeatures;

    // BitcoinNodeManager::transmitBlockHash is often called in rapid succession with the same BlockHash, therefore a simple cache is used...
    protected BlockHeaderWithTransactionCount _cachedTransmittedBlockHeader = null;

    @Override
    protected void _initNode(final BitcoinNode node) {
        super._initNode(node);
        _nodeInitializer.initializeNode(node);
    }

    @Override
    protected void _onAllNodesDisconnected() {
        Logger.log("All nodes disconnected.");

        try (final MysqlDatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
            final BitcoinNodeDatabaseManager nodeDatabaseManager = new BitcoinNodeDatabaseManager(databaseConnection);

            final MutableList<NodeFeatures.Feature> requiredFeatures = new MutableList<NodeFeatures.Feature>();
            requiredFeatures.add(NodeFeatures.Feature.BLOCKCHAIN_ENABLED);
            requiredFeatures.add(NodeFeatures.Feature.BITCOIN_CASH_ENABLED);
            final List<BitcoinNodeIpAddress> bitcoinNodeIpAddresses = nodeDatabaseManager.findNodes(requiredFeatures, _maxNodeCount);

            for (final BitcoinNodeIpAddress bitcoinNodeIpAddress : bitcoinNodeIpAddresses) {
                final Ip ip = bitcoinNodeIpAddress.getIp();
                if (ip == null) { continue; }

                final String host = ip.toString();
                final Integer port = bitcoinNodeIpAddress.getPort();
                final BitcoinNode node = new BitcoinNode(host, port, _threadPoolFactory.newThreadPool(), _localNodeFeatures);
                this.addNode(node); // NOTE: _addNotHandshakedNode(BitcoinNode) is not the same as addNode(BitcoinNode)...

                Logger.log("All nodes disconnected.  Falling back on previously-seen node: " + host + ":" + ip);
            }
        }
        catch (final DatabaseException databaseException) {
            Logger.log(databaseException);
        }
    }

    @Override
    public void _onNodeConnected(final BitcoinNode node) {
        try (final MysqlDatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
            final BlockFinderHashesBuilder blockFinderHashesBuilder = new BlockFinderHashesBuilder(databaseConnection, _databaseManagerCache);
            final List<Sha256Hash> blockFinderHashes = blockFinderHashesBuilder.createBlockFinderBlockHashes();

            node.transmitBlockFinder(blockFinderHashes);
        }
        catch (final DatabaseException exception) {
            Logger.log(exception);
        }
    }

    @Override
    protected void _onNodeDisconnected(final BitcoinNode node) {
        super._onNodeDisconnected(node);

        final Boolean handshakeIsComplete = node.handshakeIsComplete();
        if (! handshakeIsComplete) {
            final Ip ip = node.getIp();

            if (_banFilter.shouldBanIp(ip)) {
                _banFilter.banIp(ip);
            }
        }
    }

    @Override
    protected void _addHandshakedNode(final BitcoinNode node) {
        final Ip ip = node.getIp();
        final Integer port = node.getPort();

        final MutableList<BitcoinNode> allNodes = new MutableList<BitcoinNode>(_pendingNodes.values());
        allNodes.addAll(_nodes.values());

        for (final BitcoinNode bitcoinNode : allNodes) {
            final Ip existingNodeIp = bitcoinNode.getIp();
            final Integer existingNodePort = bitcoinNode.getPort();

            if (Util.areEqual(ip, existingNodeIp) && Util.areEqual(port, existingNodePort)) {
                return; // Duplicate Node...
            }
        }

        final Boolean blockchainIsEnabled = node.hasFeatureEnabled(NodeFeatures.Feature.BLOCKCHAIN_ENABLED);
        final Boolean blockchainIsSynchronized = _synchronizationStatusHandler.isBlockchainSynchronized();
        if (blockchainIsEnabled == null) {
            Logger.log("NOTICE: Unable to determine feature for node: " + node.getConnectionString());
        }

        if ( (! Util.coalesce(blockchainIsEnabled, false)) && (! blockchainIsSynchronized) ) {
            node.disconnect();
            return; // Reject SPV Nodes during the initial-sync...
        }

        super._addHandshakedNode(node);
    }

    @Override
    protected void _addNotHandshakedNode(final BitcoinNode node) {
        final Ip ip = node.getIp();
        final Integer port = node.getPort();

        try (final MysqlDatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
            final BitcoinNodeDatabaseManager nodeDatabaseManager = new BitcoinNodeDatabaseManager(databaseConnection);
            final Boolean isBanned = nodeDatabaseManager.isBanned(ip);
            if (isBanned) {
                node.disconnect();
                return;
            }

            synchronized (_mutex) {
                if (_isShuttingDown) {
                    node.disconnect();
                    return;
                }

                final MutableList<BitcoinNode> allNodes = new MutableList<BitcoinNode>(_pendingNodes.values());
                allNodes.addAll(_nodes.values());

                for (final BitcoinNode bitcoinNode : allNodes) {
                    final Ip existingNodeIp = bitcoinNode.getIp();
                    final Integer existingNodePort = bitcoinNode.getPort();

                    if (Util.areEqual(ip, existingNodeIp) && Util.areEqual(port, existingNodePort)) {
                        return; // Duplicate Node...
                    }
                }

                super._addNotHandshakedNode(node);
            }

            nodeDatabaseManager.storeNode(node);
        }
        catch (final DatabaseException databaseException) {
            Logger.log(databaseException);
        }
    }

    @Override
    protected void _onNodeHandshakeComplete(final BitcoinNode node) {
        try (final MysqlDatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
            final BitcoinNodeDatabaseManager nodeDatabaseManager = new BitcoinNodeDatabaseManager(databaseConnection);
            nodeDatabaseManager.updateLastHandshake(node);
            nodeDatabaseManager.updateNodeFeatures(node);
            nodeDatabaseManager.updateUserAgent(node);
        }
        catch (final DatabaseException databaseException) {
            Logger.log(databaseException);
        }
    }

    public BitcoinNodeManager(final Integer maxNodeCount, final MysqlDatabaseConnectionFactory databaseConnectionFactory, final DatabaseManagerCache databaseManagerCache, final MutableNetworkTime networkTime, final NodeInitializer nodeInitializer, final BanFilter banFilter, final MemoryPoolEnquirer memoryPoolEnquirer, final SynchronizationStatus synchronizationStatusHandler, final ThreadPool threadPool, final ThreadPoolFactory threadPoolFactory, final LocalNodeFeatures localNodeFeatures) {
        super(maxNodeCount, new BitcoinNodeFactory(threadPoolFactory, localNodeFeatures), networkTime, threadPool);
        _databaseConnectionFactory = databaseConnectionFactory;
        _databaseManagerCache = databaseManagerCache;
        _nodeInitializer = nodeInitializer;
        _banFilter = banFilter;
        _memoryPoolEnquirer = memoryPoolEnquirer;
        _synchronizationStatusHandler = synchronizationStatusHandler;
        _threadPoolFactory = threadPoolFactory;
        _localNodeFeatures = localNodeFeatures;
    }

    protected void _requestBlockHeaders(final List<Sha256Hash> blockHashes, final DownloadBlockHeadersCallback callback) {
        _selectNodeForRequest(new NodeApiRequest<BitcoinNode>() {
            @Override
            public void run(final BitcoinNode bitcoinNode) {
                final NodeApiRequest<BitcoinNode> apiRequest = this;

                bitcoinNode.requestBlockHeaders(blockHashes, new BitcoinNode.DownloadBlockHeadersCallback() {
                    @Override
                    public void onResult(final List<BlockHeaderWithTransactionCount> result) {
                        _onResponseReceived(bitcoinNode, apiRequest);
                        if (apiRequest.didTimeout) { return; }

                        if (callback != null) {
                            callback.onResult(result);
                        }
                    }
                });
            }

            @Override
            public void onFailure() {
                final Sha256Hash firstBlockHash = (blockHashes.isEmpty() ? null : blockHashes.get(0));
                Logger.log("Request failed: BitcoinNodeManager.requestBlockHeader("+ firstBlockHash +")");

                if (callback != null) {
                    callback.onFailure();
                }
            }
        });
    }

    public void broadcastBlockFinder(final List<Sha256Hash> blockHashes) {
        synchronized (_mutex) {
            for (final BitcoinNode bitcoinNode : _nodes.values()) {
                bitcoinNode.transmitBlockFinder(blockHashes);
            }
        }
    }

    public void requestBlockHashesAfter(final Sha256Hash blockHash) {
        _sendMessage(new NodeApiMessage<BitcoinNode>() {
            @Override
            public void run(final BitcoinNode bitcoinNode) {
                bitcoinNode.requestBlockHashesAfter(blockHash);
            }
        });
    }

    /**
     * Finds incomplete PendingBlocks that are not provided by any of the connected Nodes and attempts to locate them based on download-priority.
     */
    public void findNodeInventory() {
        final List<NodeId> connectedNodes;
        synchronized (_mutex) {
            connectedNodes = new MutableList<NodeId>(_nodes.keySet());
        }

        if (connectedNodes.isEmpty()) { return; }

        final MutableList<QueryBlocksMessage> queryBlocksMessages = new MutableList<QueryBlocksMessage>();

        try (final MysqlDatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
            final PendingBlockDatabaseManager pendingBlockDatabaseManager = new PendingBlockDatabaseManager(databaseConnection);
            final List<Tuple<Sha256Hash, Sha256Hash>> inventoryPlan = pendingBlockDatabaseManager.selectPriorityPendingBlocksWithUnknownNodeInventory(connectedNodes);

            int messagesWithoutStopBeforeHashes = 0;
            for (final Tuple<Sha256Hash, Sha256Hash> inventoryHash : inventoryPlan) {
                final QueryBlocksMessage queryBlocksMessage = new QueryBlocksMessage();
                queryBlocksMessage.addBlockHash(inventoryHash.first);
                queryBlocksMessage.setStopBeforeBlockHash(inventoryHash.second);

                if (inventoryHash.second == null) {
                    if (messagesWithoutStopBeforeHashes > 0) { break; } // NOTE: Only broadcast one QueryBlocks Message without a stopBeforeHash to support the case when BlockHeaders is not up to date...
                    messagesWithoutStopBeforeHashes += 1;
                }

                queryBlocksMessages.add(queryBlocksMessage);
            }
        }
        catch (final DatabaseException exception) {
            Logger.log(exception);
        }

        synchronized (_mutex) {
            for (final QueryBlocksMessage queryBlocksMessage : queryBlocksMessages) {
                Logger.log("Broadcasting QueryBlocksMessage: " + queryBlocksMessage.getBlockHashes().get(0) + " -> " + queryBlocksMessage.getStopBeforeBlockHash());
                for (final BitcoinNode bitcoinNode : _nodes.values()) {
                    bitcoinNode.queueMessage(queryBlocksMessage);
                }
            }
        }
    }

    protected NodeApiRequest<BitcoinNode> _createRequestBlockRequest(final Sha256Hash blockHash, final DownloadBlockCallback callback) {
        return new NodeApiRequest<BitcoinNode>() {
            protected BitcoinNode _bitcoinNode;

            @Override
            public void run(final BitcoinNode bitcoinNode) {
                _bitcoinNode = bitcoinNode;

                final NodeApiRequest<BitcoinNode> apiRequest = this;

                bitcoinNode.requestBlock(blockHash, new BitcoinNode.DownloadBlockCallback() {
                    @Override
                    public void onResult(final Block block) {
                        _onResponseReceived(bitcoinNode, apiRequest);
                        if (apiRequest.didTimeout) { return; }

                        if (callback != null) {
                            Logger.log("Received Block: "+ block.getHash() +" from Node: " + bitcoinNode.getConnectionString());
                            callback.onResult(block);
                        }
                    }

                    @Override
                    public void onFailure(final Sha256Hash blockHash) {
                        if (apiRequest.didTimeout) { return; }

                        _pendingRequestsManager.removePendingRequest(apiRequest);

                        apiRequest.onFailure();
                    }
                });
            }

            @Override
            public void onFailure() {
                Logger.log("Request failed: BitcoinNodeManager.requestBlock("+ blockHash +") " + (_bitcoinNode != null ? _bitcoinNode.getConnectionString() : "null"));

                if (callback != null) {
                    callback.onFailure(blockHash);
                }
            }
        };
    }

    protected void _requestBlock(final Sha256Hash blockHash, final DownloadBlockCallback callback) {
        _selectNodeForRequest(_createRequestBlockRequest(blockHash, callback));
    }

    protected void _requestBlock(final BitcoinNode selectedNode, final Sha256Hash blockHash, final DownloadBlockCallback callback) {
        _selectNodeForRequest(selectedNode, _createRequestBlockRequest(blockHash, callback));
    }

    protected NodeApiRequest<BitcoinNode> _createRequestTransactionsRequest(final List<Sha256Hash> transactionHashes, final DownloadTransactionCallback callback) {
        return new NodeApiRequest<BitcoinNode>() {
            @Override
            public void run(final BitcoinNode bitcoinNode) {
                final NodeApiRequest<BitcoinNode> apiRequest = this;

                bitcoinNode.requestTransactions(transactionHashes, new BitcoinNode.DownloadTransactionCallback() {
                    @Override
                    public void onResult(final Transaction result) {
                        _onResponseReceived(bitcoinNode, apiRequest);
                        if (apiRequest.didTimeout) { return; }

                        if (callback != null) {
                            callback.onResult(result);
                        }
                    }

                    @Override
                    public void onFailure(final List<Sha256Hash> transactionHashes) {
                        if (apiRequest.didTimeout) { return; }

                        _pendingRequestsManager.removePendingRequest(apiRequest);

                        apiRequest.onFailure();
                    }
                });
            }

            @Override
            public void onFailure() {
                Logger.log("Request failed: BitcoinNodeManager.requestTransactions("+ transactionHashes.get(0) +" + "+ (transactionHashes.getSize() - 1) +")");

                if (callback != null) {
                    callback.onFailure(transactionHashes);
                }
            }
        };
    }

    public void requestThinBlock(final Sha256Hash blockHash, final DownloadBlockCallback callback) {
        final NodeApiRequest<BitcoinNode> thinBlockApiRequest = new NodeApiRequest<BitcoinNode>() {
            @Override
            public void run(final BitcoinNode bitcoinNode) {
                final NodeApiRequest<BitcoinNode> apiRequest = this;

                final BloomFilter bloomFilter = _memoryPoolEnquirer.getBloomFilter(blockHash);

                bitcoinNode.requestThinBlock(blockHash, bloomFilter, new BitcoinNode.DownloadThinBlockCallback() { // TODO: Consider using ExtraThinBlocks... Unsure if the potential round-trip on a TransactionHash collision is worth it, though.
                    @Override
                    public void onResult(final BitcoinNode.ThinBlockParameters extraThinBlockParameters) {
                        _onResponseReceived(bitcoinNode, apiRequest);
                        if (apiRequest.didTimeout) { return; }

                        final BlockHeader blockHeader = extraThinBlockParameters.blockHeader;
                        final List<Sha256Hash> transactionHashes = extraThinBlockParameters.transactionHashes;
                        final List<Transaction> transactions = extraThinBlockParameters.transactions;

                        final ThinBlockAssembler thinBlockAssembler = new ThinBlockAssembler(_memoryPoolEnquirer);

                        final AssembleThinBlockResult assembleThinBlockResult = thinBlockAssembler.assembleThinBlock(blockHeader, transactionHashes, transactions);
                        if (! assembleThinBlockResult.wasSuccessful()) {
                            _selectNodeForRequest(bitcoinNode, new NodeApiRequest<BitcoinNode>() {
                                @Override
                                public void run(final BitcoinNode bitcoinNode) {
                                    final NodeApiRequest<BitcoinNode> apiRequest = this;

                                    bitcoinNode.requestThinTransactions(blockHash, assembleThinBlockResult.missingTransactions, new BitcoinNode.DownloadThinTransactionsCallback() {
                                        @Override
                                        public void onResult(final List<Transaction> missingTransactions) {
                                            _onResponseReceived(bitcoinNode, apiRequest);
                                            if (apiRequest.didTimeout) { return; }

                                            final Block block = thinBlockAssembler.reassembleThinBlock(assembleThinBlockResult, missingTransactions);
                                            if (block == null) {
                                                Logger.log("NOTICE: Falling back to traditional block.");
                                                // Fallback on downloading block traditionally...
                                                _requestBlock(blockHash, callback);
                                            }
                                            else {
                                                Logger.log("NOTICE: Thin block assembled. " + System.currentTimeMillis());
                                                if (callback != null) {
                                                    callback.onResult(assembleThinBlockResult.block);
                                                }
                                            }
                                        }
                                    });
                                }

                                @Override
                                public void onFailure() {
                                    Logger.log("NOTICE: Falling back to traditional block.");

                                    _pendingRequestsManager.removePendingRequest(apiRequest);

                                    _requestBlock(blockHash, callback);
                                }
                            });
                        }
                        else {
                            Logger.log("NOTICE: Thin block assembled on first trip. " + System.currentTimeMillis());
                            if (callback != null) {
                                callback.onResult(assembleThinBlockResult.block);
                            }
                        }
                    }
                });
            }

            @Override
            public void onFailure() {
                Logger.log("Request failed: BitcoinNodeManager.requestThinBlock("+ blockHash +")");

                if (callback != null) {
                    callback.onFailure(blockHash);
                }
            }
        };

        final Boolean shouldRequestThinBlocks;
        {
            if (! _synchronizationStatusHandler.isBlockchainSynchronized()) {
                shouldRequestThinBlocks = false;
            }
            else {
                final Integer memoryPoolTransactionCount = _memoryPoolEnquirer.getMemoryPoolTransactionCount();
                final Boolean memoryPoolIsTooEmpty = (memoryPoolTransactionCount >= MINIMUM_THIN_BLOCK_TRANSACTION_COUNT);
                shouldRequestThinBlocks = (! memoryPoolIsTooEmpty);
            }
        }

        if (shouldRequestThinBlocks) {
            final NodeFilter<BitcoinNode> nodeFilter = new NodeFilter<BitcoinNode>() {
                @Override
                public Boolean meetsCriteria(final BitcoinNode bitcoinNode) {
                    return bitcoinNode.supportsExtraThinBlocks();
                }
            };

            final BitcoinNode selectedNode;
            synchronized (_mutex) {
                selectedNode = _selectBestNode(nodeFilter);
            }

            if (selectedNode != null) {
                Logger.log("NOTICE: Requesting thin block. " + System.currentTimeMillis());
                _selectNodeForRequest(selectedNode, thinBlockApiRequest);
            }
            else {
                _requestBlock(blockHash, callback);
            }

        }
        else {
            _requestBlock(blockHash, callback);
        }
    }

    public void requestBlock(final Sha256Hash blockHash, final DownloadBlockCallback callback) {
        _requestBlock(blockHash, callback);
    }

    public void requestBlock(final BitcoinNode selectedNode, final Sha256Hash blockHash, final DownloadBlockCallback callback) {
        _requestBlock(selectedNode, blockHash, callback);
    }

    public void transmitBlockHash(final BitcoinNode bitcoinNode, final Block block) {
        if (bitcoinNode.newBlocksViaHeadersIsEnabled()) {
            bitcoinNode.transmitBlockHeader(block, block.getTransactionCount());
        }
        else {
            final MutableList<Sha256Hash> blockHashes = new MutableList<Sha256Hash>(1);
            blockHashes.add(block.getHash());
            bitcoinNode.transmitBlockHashes(blockHashes);
        }
    }

    public void transmitBlockHash(final BitcoinNode bitcoinNode, final Sha256Hash blockHash) {
        if (bitcoinNode.newBlocksViaHeadersIsEnabled()) {
            final BlockHeaderWithTransactionCount cachedBlockHeader = _cachedTransmittedBlockHeader;
            if ( (cachedBlockHeader != null) && (Util.areEqual(blockHash, cachedBlockHeader.getHash())) ) {
                bitcoinNode.transmitBlockHeader(cachedBlockHeader);
            }
            else {
                try (final MysqlDatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
                    final BlockHeaderDatabaseManager blockHeaderDatabaseManager = new BlockHeaderDatabaseManager(databaseConnection, _databaseManagerCache);
                    final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection, _databaseManagerCache);

                    final BlockId blockId = blockHeaderDatabaseManager.getBlockHeaderId(blockHash);
                    if (blockId == null) { return; } // Block Hash has not been synchronized...

                    final BlockHeader blockHeader = blockHeaderDatabaseManager.getBlockHeader(blockId);
                    final Integer transactionCount = blockDatabaseManager.getTransactionCount(blockId);
                    if (transactionCount == null) { return; } // Block Hash is currently only a header...

                    final BlockHeaderWithTransactionCount blockHeaderWithTransactionCount = new ImmutableBlockHeaderWithTransactionCount(blockHeader, transactionCount);
                    _cachedTransmittedBlockHeader = blockHeaderWithTransactionCount;

                    bitcoinNode.transmitBlockHeader(blockHeaderWithTransactionCount);
                }
                catch (final DatabaseException exception) {
                    Logger.log(exception);
                }
            }
        }
        else {
            final MutableList<Sha256Hash> blockHashes = new MutableList<Sha256Hash>(1);
            blockHashes.add(blockHash);

            bitcoinNode.transmitBlockHashes(blockHashes);
        }
    }

    public void requestBlockHeadersAfter(final Sha256Hash blockHash, final DownloadBlockHeadersCallback callback) {
        final MutableList<Sha256Hash> blockHashes = new MutableList<Sha256Hash>(1);
        blockHashes.add(blockHash);

        _requestBlockHeaders(blockHashes, callback);
    }

    public void requestBlockHeadersAfter(final List<Sha256Hash> blockHashes, final DownloadBlockHeadersCallback callback) {
        _requestBlockHeaders(blockHashes, callback);
    }

    public void requestTransactions(final List<Sha256Hash> transactionHashes, final DownloadTransactionCallback callback) {
        if (transactionHashes.isEmpty()) { return; }

        _selectNodeForRequest(_createRequestTransactionsRequest(transactionHashes, callback));
    }

    public void requestTransactions(final BitcoinNode selectedNode, final List<Sha256Hash> transactionHashes, final DownloadTransactionCallback callback) {
        if (transactionHashes.isEmpty()) { return; }

        _selectNodeForRequest(selectedNode, _createRequestTransactionsRequest(transactionHashes, callback));
    }

    public void banNode(final Ip ip) {
        try (final MysqlDatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
            final BitcoinNodeDatabaseManager nodeDatabaseManager = new BitcoinNodeDatabaseManager(databaseConnection);

            // Ban any nodes from that ip...
            nodeDatabaseManager.setIsBanned(ip, true);
        }
        catch (final DatabaseException databaseException) {
            Logger.log(databaseException);
            // Still continue to disconnect from any nodes at that ip, even upon an error...
        }

        // Disconnect all currently-connected nodes at that ip...
        synchronized (_mutex) {
            final MutableList<BitcoinNode> droppedNodes = new MutableList<BitcoinNode>();

            for (final NodeId nodeId : _nodes.keySet()) {
                final BitcoinNode bitcoinNode = _nodes.get(nodeId);
                if (Util.areEqual(ip, bitcoinNode.getIp())) {
                    droppedNodes.add(bitcoinNode);
                }
            }

            for (final BitcoinNode bitcoinNode : droppedNodes) {
                _removeNode(bitcoinNode);
            }
        }
    }

    public void unbanNode(final Ip ip) {
        try (final MysqlDatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
            final BitcoinNodeDatabaseManager nodeDatabaseManager = new BitcoinNodeDatabaseManager(databaseConnection);

            // Unban any nodes from that ip...
            nodeDatabaseManager.setIsBanned(ip, false);
        }
        catch (final DatabaseException databaseException) {
            Logger.log(databaseException);
        }
    }
}
