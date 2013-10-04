/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.HashTableLoaderFactory;
import org.apache.hadoop.hive.ql.exec.persistence.MapJoinKey;
import org.apache.hadoop.hive.ql.exec.persistence.MapJoinObjectSerDeContext;
import org.apache.hadoop.hive.ql.exec.persistence.MapJoinRowContainer;
import org.apache.hadoop.hive.ql.exec.persistence.MapJoinTableContainer;
import org.apache.hadoop.hive.ql.exec.persistence.MapJoinTableContainerSerDe;
import org.apache.hadoop.hive.ql.exec.tez.TezProcessor;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.MapJoinDesc;
import org.apache.hadoop.hive.ql.plan.TableDesc;
import org.apache.hadoop.hive.ql.plan.api.OperatorType;
import org.apache.hadoop.hive.serde2.SerDe;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.tez.runtime.api.LogicalInput;

/**
 * Map side Join operator implementation.
 */
public class MapJoinOperator extends AbstractMapJoinOperator<MapJoinDesc> implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final Log LOG = LogFactory.getLog(MapJoinOperator.class.getName());

  private transient String tableKey;
  private transient String serdeKey;
  private transient ObjectCache cache;

  private HashTableLoader loader;

  private static final transient String[] FATAL_ERR_MSG = {
      null, // counter value 0 means no error
      "Mapside join exceeds available memory. "
          + "Please try removing the mapjoin hint."};

  private transient MapJoinTableContainer[] mapJoinTables;
  private transient MapJoinTableContainerSerDe[] mapJoinTableSerdes;
  private transient boolean hashTblInitedOnce;
  private transient MapJoinKey key;

  public MapJoinOperator() {
  }

  public MapJoinOperator(AbstractMapJoinOperator<? extends MapJoinDesc> mjop) {
    super(mjop);
  }

  @Override
  protected void initializeOp(Configuration hconf) throws HiveException {
    super.initializeOp(hconf);

    int tagLen = conf.getTagLength();

    tableKey = "__HASH_MAP_"+this.getOperatorId()+"_container";
    serdeKey = "__HASH_MAP_"+this.getOperatorId()+"_serde";

    cache = ObjectCacheFactory.getCache(hconf);
    loader = HashTableLoaderFactory.getLoader(hconf);

    mapJoinTables = (MapJoinTableContainer[]) cache.retrieve(tableKey);
    mapJoinTableSerdes = (MapJoinTableContainerSerDe[]) cache.retrieve(serdeKey);
    hashTblInitedOnce = true;

    if (mapJoinTables == null || mapJoinTableSerdes == null) {
      mapJoinTables = new MapJoinTableContainer[tagLen];
      mapJoinTableSerdes = new MapJoinTableContainerSerDe[tagLen];
      hashTblInitedOnce = false;
    }
  }

  @Override
  protected void fatalErrorMessage(StringBuilder errMsg, long counterCode) {
    errMsg.append("Operator " + getOperatorId() + " (id=" + id + "): "
        + FATAL_ERR_MSG[(int) counterCode]);
  }

  public void generateMapMetaData() throws HiveException, SerDeException {
    // generate the meta data for key
    // index for key is -1

    TableDesc keyTableDesc = conf.getKeyTblDesc();
    SerDe keySerializer = (SerDe) ReflectionUtils.newInstance(keyTableDesc.getDeserializerClass(),
        null);
    keySerializer.initialize(null, keyTableDesc.getProperties());
    MapJoinObjectSerDeContext keyContext = new MapJoinObjectSerDeContext(keySerializer, false);
    for (int pos = 0; pos < order.length; pos++) {
      if (pos == posBigTable) {
        continue;
      }
      TableDesc valueTableDesc;
      if (conf.getNoOuterJoin()) {
        valueTableDesc = conf.getValueTblDescs().get(pos);
      } else {
        valueTableDesc = conf.getValueFilteredTblDescs().get(pos);
      }
      SerDe valueSerDe = (SerDe) ReflectionUtils.newInstance(valueTableDesc.getDeserializerClass(),
          null);
      valueSerDe.initialize(null, valueTableDesc.getProperties());
      MapJoinObjectSerDeContext valueContext = new MapJoinObjectSerDeContext(valueSerDe, hasFilter(pos));
      mapJoinTableSerdes[pos] = new MapJoinTableContainerSerDe(keyContext, valueContext);
    }
  }

  private void loadHashTable(Map<String, LogicalInput> inputs) throws HiveException {

    //FIXME the input needs to be mapped to corresponding op
    for (int pos = 0; pos < mapJoinTables.length; pos++) {
      if (pos == posBigTable) {
        System.out.println("XXX: Input was big table pos: " + posBigTable);
        continue;
      }

      System.out.println("XXX: Going to load small table for position: " + pos);
      LogicalInput input = inputs.get(conf.getParentToInput().get(pos));

      try {
        mapJoinTables[pos] = mapJoinTableSerdes[pos].load(input);
      } catch (IOException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      } catch (SerDeException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
  }

  private void loadHashTable() throws HiveException {

    if (!this.getExecContext().getLocalWork().getInputFileChangeSensitive()) {
      if (hashTblInitedOnce) {
        return;
      } else {
        hashTblInitedOnce = true;
      }
    }

    loader.load(this.getExecContext(), hconf, this.getConf(),
        posBigTable, mapJoinTables, mapJoinTableSerdes);
    cache.cache(tableKey, mapJoinTables);
    cache.cache(serdeKey, mapJoinTableSerdes);
  }

  // Load the hash table
  @Override
  public void cleanUpInputFileChangedOp() throws HiveException {
    System.out.println("XXX: We are going to be loading the hash map");
    try {
      if (firstRow) {
        // generate the map metadata
        generateMapMetaData();
        firstRow = false;
      }
      loadHashTable(TezProcessor.inputs);
    } catch (SerDeException e) {
      throw new HiveException(e);
    }
  }

  @Override
  public void processOp(Object row, int tag) throws HiveException {
    try {
      if (firstRow) {
        // generate the map metadata
        generateMapMetaData();
        firstRow = false;
      }
      alias = (byte)tag;

      // compute keys and values as StandardObjects
      key = JoinUtil.computeMapJoinKeys(key, row, joinKeys[alias],
          joinKeysObjectInspectors[alias]);
      boolean joinNeeded = false;
      for (byte pos = 0; pos < order.length; pos++) {
        if (pos != alias) {
          MapJoinRowContainer rowContainer = mapJoinTables[pos].get(key);
          // there is no join-value or join-key has all null elements
          if (rowContainer == null || key.hasAnyNulls(nullsafes)) {
            if (!noOuterJoin) {
              joinNeeded = true;
              storage[pos] = dummyObjVectors[pos];
            } else {
              storage[pos] = emptyList;
            }
          } else {
            joinNeeded = true;
            storage[pos] = rowContainer.copy();
            aliasFilterTags[pos] = rowContainer.getAliasFilter();
          }
        }
      }
      if (joinNeeded) {
        ArrayList<Object> value = getFilteredValue(alias, row);
        // Add the value to the ArrayList
        storage[alias].add(value);
        // generate the output records
        checkAndGenObject();
      }
      // done with the row
      storage[tag].clear();
      for (byte pos = 0; pos < order.length; pos++) {
        if (pos != tag) {
          storage[pos] = null;
        }
      }
    } catch (SerDeException e) {
      throw new HiveException(e);
    }
  }

  @Override
  public void closeOp(boolean abort) throws HiveException {
    if (mapJoinTables != null) {
      for (MapJoinTableContainer tableContainer : mapJoinTables) {
        if (tableContainer != null) {
          tableContainer.clear();
        }
      }
    }
    super.closeOp(abort);
  }

  /**
   * Implements the getName function for the Node Interface.
   *
   * @return the name of the operator
   */
  @Override
  public String getName() {
    return getOperatorName();
  }

  static public String getOperatorName() {
    return "MAPJOIN";
  }

  @Override
  public OperatorType getType() {
    return OperatorType.MAPJOIN;
  }
}
