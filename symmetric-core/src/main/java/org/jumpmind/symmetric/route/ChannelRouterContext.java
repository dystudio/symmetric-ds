/**
 * Licensed to JumpMind Inc under one or more contributor
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU General Public License, version 3.0 (GPLv3)
 * (the "License"); you may not use this file except in compliance
 * with the License.
 *
 * You should have received a copy of the GNU General Public License,
 * version 3.0 (GPLv3) along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jumpmind.symmetric.route;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jumpmind.db.sql.ISqlTransaction;
import org.jumpmind.db.sql.SqlException;
import org.jumpmind.symmetric.SymmetricException;
import org.jumpmind.symmetric.model.Data;
import org.jumpmind.symmetric.model.DataEvent;
import org.jumpmind.symmetric.model.DataGap;
import org.jumpmind.symmetric.model.DataMetaData;
import org.jumpmind.symmetric.model.Node;
import org.jumpmind.symmetric.model.NodeChannel;
import org.jumpmind.symmetric.model.OutgoingBatch;
import org.jumpmind.symmetric.model.TriggerRouter;
import org.slf4j.Logger;

public class ChannelRouterContext extends SimpleRouterContext {

    public static final String STAT_INSERT_DATA_EVENTS_MS = "data.events.insert.time.ms";
    public static final String STAT_DATA_ROUTER_MS = "data.router.time.ms";
    public static final String STAT_QUERY_TIME_MS = "data.read.query.time.ms";
    public static final String STAT_READ_DATA_MS = "data.read.total.time.ms";
    public static final String STAT_REREAD_DATA_MS = "data.reread.time.ms";
    public static final String STAT_ENQUEUE_DATA_MS = "data.enqueue.time.ms";
    public static final String STAT_ENQUEUE_EOD_MS = "data.enqueue.eod.time.ms";
    public static final String STAT_DATA_EVENTS_INSERTED = "data.events.insert.count";
    public static final String STAT_DATA_ROUTED_COUNT = "data.routed.count";
    public static final String STAT_ROUTE_TOTAL_TIME = "total.time.ms";

    private Map<String, OutgoingBatch> batchesByNodes = new HashMap<String, OutgoingBatch>();
    private Map<Integer, Map<String, OutgoingBatch>> batchesByGroups = new HashMap<Integer, Map<String, OutgoingBatch>>();
    private Map<TriggerRouter, Set<Node>> availableNodes = new HashMap<TriggerRouter, Set<Node>>();
    private Set<IDataRouter> usedDataRouters = new HashSet<IDataRouter>();
    private ISqlTransaction sqlTransaction;
    private boolean needsCommitted = false;
    private long createdTimeInMs = System.currentTimeMillis();
    private Data lastDataProcessed;
    private List<DataEvent> dataEventsToSend = new ArrayList<DataEvent>();
    private boolean produceCommonBatches = false;
    private boolean produceGroupBatches = false;
    private boolean onlyDefaultRoutersAssigned = false;
    private boolean overrideContainsBigLob = false;
    private long lastLoadId = -1;
    private long startDataId;   
    private long endDataId;
    private long dataReadCount;
    private long peekAheadFillCount;
    private long maxPeekAheadQueueSize;
    private long dataRereadCount;
    private List<DataGap> dataGaps = new ArrayList<DataGap>();
    private long lastDataId = -1;
    private List<Long> dataIds = new ArrayList<Long>();
    private List<Long> uncommittedDataIds = new ArrayList<Long>();
    private long uncommittedDataEventCount = 0;
    private long committedDataEventCount = 0;
    private Map<String, IBatchAlgorithm> batchAlgorithms;

    public ChannelRouterContext(String nodeId, NodeChannel channel, ISqlTransaction transaction, Map<String, IBatchAlgorithm> batchAlgorithms)
            throws SQLException {
        super(nodeId, channel);
        this.sqlTransaction = transaction;
        this.sqlTransaction.setInBatchMode(true);
        this.batchAlgorithms = batchAlgorithms;
    }

    public List<DataEvent> getDataEventList() {
        return dataEventsToSend;
    }

    public void clearDataEventsList() {
        dataEventsToSend.clear();
    }

    public void addDataEvent(long dataId, long batchId) {
        dataEventsToSend.add(new DataEvent(dataId, batchId));
        if (dataId != lastDataId) {
            uncommittedDataIds.add(dataId);
            lastDataId = dataId;
        }
        uncommittedDataEventCount++;
    }

    public void addData(long dataId) {
        if (dataId != lastDataId) {
            uncommittedDataIds.add(dataId);
            lastDataId = dataId;
        }
    }

    public long getCommittedDataEventCount() {
        return this.committedDataEventCount;
    }

    public Map<String, OutgoingBatch> getBatchesByNodes() {
        return batchesByNodes;
    }

    public Map<Integer, Map<String, OutgoingBatch>> getBatchesByGroups() {
        return batchesByGroups;
    }

    public Map<TriggerRouter, Set<Node>> getAvailableNodes() {
        return availableNodes;
    }

    public void commit() {
        try {
            sqlTransaction.commit();
            dataIds.addAll(uncommittedDataIds);
            committedDataEventCount += uncommittedDataEventCount;
        } finally {
            clearState();
        }
    }

    protected void clearState() {
        this.usedDataRouters.clear();
        this.encountedTransactionBoundary = false;
        this.requestGapDetection = false;
        this.batchesByNodes.clear();
        this.batchesByGroups.clear();
        this.availableNodes.clear();
        this.dataEventsToSend.clear();
        this.uncommittedDataIds.clear();
        this.uncommittedDataEventCount = 0;
    }

    public void rollback() {
        try {
            sqlTransaction.rollback();
        } catch (SqlException e) {
            log.warn("Rollback attempt failed", e);
        } finally {
            clearState();
        }
    }

    public void cleanup() {
        try {
            this.sqlTransaction.commit();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SymmetricException(ex);
        } finally {
            this.sqlTransaction.close();
        }
    }

    @Override
    synchronized public void logStats(Logger log, long totalTimeInMs) {
        super.logStats(log, totalTimeInMs);
        if (log.isDebugEnabled()) {
            log.debug(channel.getChannelId() + ", startDataId=" + startDataId + ", endDataId=" + endDataId + 
                    ", dataReadCount=" + dataReadCount + ", peekAheadFillCount=" + peekAheadFillCount +
                    ", dataGaps=" + dataGaps.toString()); 
        }
    }

    public void setNeedsCommitted(boolean b) {
        this.needsCommitted = b;
    }

    public boolean isNeedsCommitted() {
        return needsCommitted;
    }

    public Set<IDataRouter> getUsedDataRouters() {
        return usedDataRouters;
    }

    public void addUsedDataRouter(IDataRouter dataRouter) {
        this.usedDataRouters.add(dataRouter);
    }

    public void resetForNextData() {
        this.needsCommitted = false;
    }

    public long getCreatedTimeInMs() {
        return createdTimeInMs;
    }

    public void setLastDataProcessed(Data lastDataProcessed) {
        this.lastDataProcessed = lastDataProcessed;
    }
    
    public Data getLastDataProcessed() {
        return lastDataProcessed;
    }

    public ISqlTransaction getSqlTransaction() {
        return sqlTransaction;
    }

    public void setProduceCommonBatches(boolean defaultRoutersOnly) {
        this.produceCommonBatches = defaultRoutersOnly;
    }

    public boolean isProduceCommonBatches() {
        return produceCommonBatches;
    }    

    public void setProduceGroupBatches(boolean produceGroupBatches) {
        this.produceGroupBatches = produceGroupBatches;
    }

    public boolean isProduceGroupBatches() {
        return produceGroupBatches;
    }    

    public void setLastLoadId(long lastLoadId) {
        this.lastLoadId = lastLoadId;
    }
    
    public long getLastLoadId() {
        return lastLoadId;
    }

    public long getStartDataId() {
        return startDataId;
    }

    public void setStartDataId(long startDataId) {
        this.startDataId = startDataId;
    }

    public long getEndDataId() {
        return endDataId;
    }

    public void setEndDataId(long endDataId) {
        this.endDataId = endDataId;
    }

    public long getDataReadCount() {
        return dataReadCount;
    }

    public void incrementDataReadCount(long dataReadCount) {
        this.dataReadCount += dataReadCount;
    }
    
    public long getDataRereadCount() {
        return dataRereadCount;
    }
    
    public void incrementDataRereadCount() {
        this.dataRereadCount++;
    }

    public long getPeekAheadFillCount() {
        return peekAheadFillCount;
    }
    
    public long getMaxPeekAheadQueueSize() {
        return maxPeekAheadQueueSize;
    }
    
    public void setMaxPeekAheadQueueSize(long maxPeekAheadQueueSize) {
        this.maxPeekAheadQueueSize = maxPeekAheadQueueSize;
    }

    public void incrementPeekAheadFillCount(long peekAheadFillCount) {
        this.peekAheadFillCount += peekAheadFillCount;
    }

    public List<DataGap> getDataGaps() {
        return dataGaps;
    }

    public void setDataGaps(List<DataGap> dataGaps) {
        this.dataGaps = dataGaps;
    }
    
    public void setOnlyDefaultRoutersAssigned(boolean onlyDefaultRoutersAssigned) {
        this.onlyDefaultRoutersAssigned = onlyDefaultRoutersAssigned;
    }
    
    public boolean isOnlyDefaultRoutersAssigned() {
        return onlyDefaultRoutersAssigned;
    }

    public List<Long> getDataIds() {
        return dataIds;
    }

    public boolean isOverrideContainsBigLob() {
        return overrideContainsBigLob;
    }

    public void setOverrideContainsBigLob(boolean overrideContainsBigLob) {
        this.overrideContainsBigLob = overrideContainsBigLob;
    }
    
    public Map<String, IBatchAlgorithm> getBatchAlgorithms() {
        return batchAlgorithms;
    }

    public boolean isBatchComplete(OutgoingBatch batch, DataMetaData dataMetaData) {
        return batchAlgorithms.get(channel.getBatchAlgorithm()).isBatchComplete(batch, dataMetaData, this);
    }
}
