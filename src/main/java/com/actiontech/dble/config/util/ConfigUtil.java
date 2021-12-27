/*
 * Copyright (C) 2016-2022 ActionTech.
 * based on code by MyCATCopyrightHolder Copyright (c) 2013, OpenCloudDB/MyCAT.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */
package com.actiontech.dble.config.util;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.backend.datasource.PhysicalDbGroup;
import com.actiontech.dble.backend.datasource.PhysicalDbInstance;
import com.actiontech.dble.backend.datasource.ShardingNode;
import com.actiontech.dble.backend.mysql.VersionUtil;
import com.actiontech.dble.config.DbleTempConfig;
import com.actiontech.dble.config.helper.GetAndSyncDbInstanceKeyVariables;
import com.actiontech.dble.config.helper.KeyVariables;
import com.actiontech.dble.config.model.SystemConfig;
import com.actiontech.dble.config.model.db.type.DataBaseType;
import com.actiontech.dble.services.manager.response.ReloadConfig;
import com.actiontech.dble.singleton.TraceManager;
import com.actiontech.dble.util.StringUtil;
import com.google.common.collect.Maps;
import org.apache.logging.log4j.util.Strings;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public final class ConfigUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigUtil.class);

    private ConfigUtil() {
    }

    public static String filter(String text) {
        return filter(text, System.getProperties());
    }

    public static String filter(String text, Properties properties) {
        StringBuilder s = new StringBuilder();
        int cur = 0;
        int textLen = text.length();
        int propStart;
        int propStop;
        String propName;
        String propValue;
        for (; cur < textLen; cur = propStop + 1) {
            propStart = text.indexOf("${", cur);
            if (propStart < 0) {
                break;
            }
            s.append(text, cur, propStart);
            propStop = text.indexOf("}", propStart);
            if (propStop < 0) {
                throw new ConfigException("Unterminated property: " + text.substring(propStart));
            }
            propName = text.substring(propStart + 2, propStop);
            propValue = properties.getProperty(propName);
            if (propValue == null) {
                s.append("${").append(propName).append('}');
            } else {
                s.append(propValue);
            }
        }
        return s.append(text.substring(cur)).toString();
    }

    public static void setSchemasForPool(Map<String, PhysicalDbGroup> dbGroupMap, Map<String, ShardingNode> shardingNodeMap) {
        for (PhysicalDbGroup dbGroup : dbGroupMap.values()) {
            dbGroup.setSchemas(getShardingNodeSchemasOfDbGroup(dbGroup.getGroupName(), shardingNodeMap));
        }
    }

    private static ArrayList<String> getShardingNodeSchemasOfDbGroup(String dbGroup, Map<String, ShardingNode> shardingNodeMap) {
        ArrayList<String> schemaList = new ArrayList<>(30);
        for (ShardingNode dn : shardingNodeMap.values()) {
            if (dn.getDbGroup() != null && dn.getDbGroup().getGroupName().equals(dbGroup)) {
                schemaList.add(dn.getDatabase());
            }
        }
        return schemaList;
    }

    public static String getAndSyncKeyVariables(List<ReloadConfig.ChangeItem> changeItemList, boolean needSync) throws Exception {
        TraceManager.TraceObject traceObject = TraceManager.threadTrace("sync-key-variables");
        try {
            String msg = null;
            List<ReloadConfig.ChangeItem> needCheckItemList = changeItemList.stream()
                    //add dbInstance or add dbGroup or (update dbInstance and need testConn)
                    .filter(changeItem -> ((changeItem.getItem() instanceof PhysicalDbInstance || changeItem.getItem() instanceof PhysicalDbGroup) && changeItem.getType() == 1) ||
                            (changeItem.getItem() instanceof PhysicalDbInstance && changeItem.getType() == 2 && changeItem.isAffectTestConn()))
                    .collect(Collectors.toList());
            if (changeItemList.size() == 0 || needCheckItemList == null || needCheckItemList.isEmpty()) {
                //with no dbGroups, do not check the variables
                return null;
            }
            Map<String, Future<KeyVariables>> keyVariablesTaskMap = Maps.newHashMap();
            getAndSyncKeyVariablesForDataSources(needCheckItemList, keyVariablesTaskMap, needSync);

            boolean lowerCase = false;
            boolean isFirst = true;
            Set<String> firstGroup = new HashSet<>();
            Set<String> secondGroup = new HashSet<>();
            int minNodePacketSize = Integer.MAX_VALUE;
            int minVersion = Integer.parseInt(SystemConfig.getInstance().getFakeMySQLVersion().substring(0, 1));
            for (Map.Entry<String, Future<KeyVariables>> entry : keyVariablesTaskMap.entrySet()) {
                String dataSourceName = entry.getKey();
                Future<KeyVariables> future = entry.getValue();
                KeyVariables keyVariables = future.get();
                if (keyVariables != null) {
                    if (isFirst) {
                        lowerCase = keyVariables.isLowerCase();
                        if (lowerCase != DbleServer.getInstance().getConfig().isLowerCase()) {
                            secondGroup.add(dataSourceName);
                        }
                        isFirst = false;
                        firstGroup.add(dataSourceName);
                    } else if (keyVariables.isLowerCase() != lowerCase) {
                        secondGroup.add(dataSourceName);
                    }
                    minNodePacketSize = Math.min(minNodePacketSize, keyVariables.getMaxPacketSize());
                    int version = Integer.parseInt(keyVariables.getVersion().substring(0, 1));
                    minVersion = Math.min(minVersion, version);
                }
            }
            if (minNodePacketSize < SystemConfig.getInstance().getMaxPacketSize() + KeyVariables.MARGIN_PACKET_SIZE) {
                SystemConfig.getInstance().setMaxPacketSize(minNodePacketSize - KeyVariables.MARGIN_PACKET_SIZE);
                msg = "dble's maxPacketSize will be set to (the min of all dbGroup's max_allowed_packet) - " + KeyVariables.MARGIN_PACKET_SIZE + ":" + (minNodePacketSize - KeyVariables.MARGIN_PACKET_SIZE);
                LOGGER.warn(msg);
            }
            if (minVersion < Integer.parseInt(SystemConfig.getInstance().getFakeMySQLVersion().substring(0, 1))) {
                throw new ConfigException("the dble version[=" + SystemConfig.getInstance().getFakeMySQLVersion() + "] cannot be higher than the minimum version of the backend mysql node,pls check the backend mysql node.");
            }
            DbleTempConfig.getInstance().setLowerCase(lowerCase);
            if (secondGroup.size() != 0) {
                // if all datasoure's lower case are not equal, throw exception
                StringBuilder sb = new StringBuilder("The values of lower_case_table_names for backend MySQLs are different.");
                String firstGroupValue;
                String secondGroupValue;
                if (lowerCase) {
                    firstGroupValue = " not 0 :";
                    secondGroupValue = " 0 :";
                } else {
                    firstGroupValue = " 0 :";
                    secondGroupValue = " not 0 :";
                }
                sb.append("These MySQL's value is");
                sb.append(firstGroupValue);
                sb.append(Strings.join(firstGroup, ','));
                sb.append(".And these MySQL's value is");
                sb.append(secondGroupValue);
                sb.append(Strings.join(secondGroup, ','));
                sb.append(".");
                throw new IOException(sb.toString());
            }
            return msg;
        } finally {
            TraceManager.finishSpan(traceObject);
        }
    }

    public static String getAndSyncKeyVariables(Map<String, PhysicalDbGroup> dbGroups, boolean needSync) throws Exception {
        TraceManager.TraceObject traceObject = TraceManager.threadTrace("sync-key-variables");
        try {
            StringBuilder sb = new StringBuilder();
            Map<String, PhysicalDbGroup> mysqlDbGroups = new HashMap<>();
            Map<String, PhysicalDbGroup> clickHouseDbGroups = new HashMap<>();
            dbGroups.forEach((k, v) -> {
                if (v.getDbGroupConfig().instanceDatabaseType() == DataBaseType.MYSQL) {
                    mysqlDbGroups.put(k, v);
                } else {
                    clickHouseDbGroups.put(k, v);
                }
            });
            String mysqlSyncKeyVariables = getMysqlSyncKeyVariables(mysqlDbGroups, needSync);
            if (!StringUtil.isEmpty(mysqlSyncKeyVariables)) {
                sb.append(mysqlSyncKeyVariables);
            }
            String clickHouseSyncKeyVariables = getClickHouseSyncKeyVariables(clickHouseDbGroups, needSync);
            if (!StringUtil.isEmpty(clickHouseSyncKeyVariables)) {
                sb.append(clickHouseSyncKeyVariables);
            }
            return sb.length() == 0 ? null : sb.toString();
        } finally {
            TraceManager.finishSpan(traceObject);
        }
    }

    @Nullable
    private static String getMysqlSyncKeyVariables(Map<String, PhysicalDbGroup> dbGroups, boolean needSync) throws InterruptedException, ExecutionException, IOException {
        String msg = null;
        if (dbGroups.size() == 0) {
            //with no dbGroups, do not check the variables
            return null;
        }
        Map<String, Future<KeyVariables>> keyVariablesTaskMap = new HashMap<>(dbGroups.size());
        getAndSyncKeyVariablesForDataSources(dbGroups, keyVariablesTaskMap, needSync);

        boolean lowerCase = false;
        boolean isFirst = true;
        Set<String> firstGroup = new HashSet<>();
        Set<String> secondGroup = new HashSet<>();
        int minNodePacketSize = Integer.MAX_VALUE;
        int minVersion = VersionUtil.getMajorVersion(SystemConfig.getInstance().getFakeMySQLVersion());
        for (Map.Entry<String, Future<KeyVariables>> entry : keyVariablesTaskMap.entrySet()) {
            String dataSourceName = entry.getKey();
            Future<KeyVariables> future = entry.getValue();
            KeyVariables keyVariables = future.get();
            if (keyVariables != null) {
                if (isFirst) {
                    lowerCase = keyVariables.isLowerCase();
                    isFirst = false;
                    firstGroup.add(dataSourceName);
                } else if (keyVariables.isLowerCase() != lowerCase) {
                    secondGroup.add(dataSourceName);
                }
                minNodePacketSize = Math.min(minNodePacketSize, keyVariables.getMaxPacketSize());
                Integer majorVersion = VersionUtil.getMajorVersionWithoutDefaultValue(keyVariables.getVersion());
                if (majorVersion == null) {
                    LOGGER.warn("the backend mysql server version  [{}] is unrecognized, we will treat as default official  mysql version 5.*. ", keyVariables.getVersion());
                    majorVersion = 5;
                }
                minVersion = Math.min(minVersion, majorVersion);
            }
        }
        if (minNodePacketSize < SystemConfig.getInstance().getMaxPacketSize() + KeyVariables.MARGIN_PACKET_SIZE) {
            SystemConfig.getInstance().setMaxPacketSize(minNodePacketSize - KeyVariables.MARGIN_PACKET_SIZE);
            msg = "dble's maxPacketSize will be set to (the min of all dbGroup's max_allowed_packet) - " + KeyVariables.MARGIN_PACKET_SIZE + ":" + (minNodePacketSize - KeyVariables.MARGIN_PACKET_SIZE);
            LOGGER.warn(msg);
        }
        if (minVersion < VersionUtil.getMajorVersion(SystemConfig.getInstance().getFakeMySQLVersion())) {
            throw new ConfigException("the dble version[=" + SystemConfig.getInstance().getFakeMySQLVersion() + "] cannot be higher than the minimum version of the backend mysql node,pls check the backend mysql node.");
        }
        DbleTempConfig.getInstance().setLowerCase(lowerCase);
        if (secondGroup.size() != 0) {
            // if all datasoure's lower case are not equal, throw exception
            StringBuilder sb = new StringBuilder("The values of lower_case_table_names for backend MySQLs are different.");
            String firstGroupValue;
            String secondGroupValue;
            if (lowerCase) {
                firstGroupValue = " not 0 :";
                secondGroupValue = " 0 :";
            } else {
                firstGroupValue = " 0 :";
                secondGroupValue = " not 0 :";
            }
            sb.append("These MySQL's value is");
            sb.append(firstGroupValue);
            sb.append(Strings.join(firstGroup, ','));
            sb.append(".And these MySQL's value is");
            sb.append(secondGroupValue);
            sb.append(Strings.join(secondGroup, ','));
            sb.append(".");
            throw new IOException(sb.toString());
        }
        return msg;
    }

    @Nullable
    private static String getClickHouseSyncKeyVariables(Map<String, PhysicalDbGroup> dbGroups, boolean needSync) throws InterruptedException, ExecutionException, IOException {
        String msg = null;
        if (dbGroups.size() == 0) {
            //with no dbGroups, do not check the variables
            return null;
        }
        Map<String, Future<KeyVariables>> keyVariablesTaskMap = new HashMap<>(dbGroups.size());
        getAndSyncKeyVariablesForDataSources(dbGroups, keyVariablesTaskMap, needSync);

        boolean lowerCase = false;
        boolean isFirst = true;
        Set<String> firstGroup = new HashSet<>();
        Set<String> secondGroup = new HashSet<>();
        int minNodePacketSize = Integer.MAX_VALUE;
        int minVersion = VersionUtil.getMajorVersion(SystemConfig.getInstance().getFakeMySQLVersion());
        for (Map.Entry<String, Future<KeyVariables>> entry : keyVariablesTaskMap.entrySet()) {
            String dataSourceName = entry.getKey();
            Future<KeyVariables> future = entry.getValue();
            KeyVariables keyVariables = future.get();
            if (keyVariables != null) {
                if (isFirst) {
                    lowerCase = keyVariables.isLowerCase();
                    isFirst = false;
                    firstGroup.add(dataSourceName);
                } else if (keyVariables.isLowerCase() != lowerCase) {
                    secondGroup.add(dataSourceName);
                }
                minNodePacketSize = Math.min(minNodePacketSize, keyVariables.getMaxPacketSize());
                Integer majorVersion = VersionUtil.getMajorVersionWithoutDefaultValue(keyVariables.getVersion());
                if (majorVersion == null) {
                    LOGGER.warn("the backend clickhouse server version  [{}] is unrecognized, we will treat as default official  clickhouse version 5.*. ", keyVariables.getVersion());
                    majorVersion = 5;
                }
                minVersion = Math.min(minVersion, majorVersion);
            }
        }
        if (minNodePacketSize < SystemConfig.getInstance().getMaxPacketSize() + KeyVariables.MARGIN_PACKET_SIZE) {
            SystemConfig.getInstance().setMaxPacketSize(minNodePacketSize - KeyVariables.MARGIN_PACKET_SIZE);
            msg = "dble's maxPacketSize will be set to (the min of all dbGroup's max_allowed_packet) - " + KeyVariables.MARGIN_PACKET_SIZE + ":" + (minNodePacketSize - KeyVariables.MARGIN_PACKET_SIZE);
            LOGGER.warn(msg);
        }
        if (minVersion < VersionUtil.getMajorVersion(SystemConfig.getInstance().getFakeMySQLVersion())) {
            throw new ConfigException("the dble version[=" + SystemConfig.getInstance().getFakeMySQLVersion() + "] cannot be higher than the minimum version of the backend clickHouse node,pls check the backend clickHouse node.");
        }
        if (secondGroup.size() != 0) {
            // if all datasoure's lower case are not equal, throw exception
            StringBuilder sb = new StringBuilder("The values of lower_case_table_names for backend clickHouse are different.");
            String firstGroupValue;
            String secondGroupValue;
            if (lowerCase) {
                firstGroupValue = " not 0 :";
                secondGroupValue = " 0 :";
            } else {
                firstGroupValue = " 0 :";
                secondGroupValue = " not 0 :";
            }
            sb.append("These clickHouse's value is");
            sb.append(firstGroupValue);
            sb.append(Strings.join(firstGroup, ','));
            sb.append(".And these clickHouse's value is");
            sb.append(secondGroupValue);
            sb.append(Strings.join(secondGroup, ','));
            sb.append(".");
            throw new IOException(sb.toString());
        }
        return msg;
    }


    private static void getAndSyncKeyVariablesForDataSources(List<ReloadConfig.ChangeItem> changeItemList, Map<String, Future<KeyVariables>> keyVariablesTaskMap,
                                                             boolean needSync) throws InterruptedException {
        ExecutorService service = Executors.newFixedThreadPool(changeItemList.size());
        for (ReloadConfig.ChangeItem changeItem : changeItemList) {
            if (changeItem.getItem() instanceof PhysicalDbInstance) {
                PhysicalDbInstance ds = (PhysicalDbInstance) changeItem.getItem();
                if (ds.isDisabled() || !ds.isTestConnSuccess() || ds.isFakeNode()) {
                    continue;
                }
                getKeyVariablesForDataSource(service, ds, ds.getDbGroupConfig().getName(), keyVariablesTaskMap, needSync);
            } else if (changeItem.getItem() instanceof PhysicalDbGroup) {
                PhysicalDbGroup dbGroup = (PhysicalDbGroup) changeItem.getItem();
                for (PhysicalDbInstance ds : dbGroup.getAllDbInstanceMap().values()) {
                    if (ds.isDisabled() || !ds.isTestConnSuccess() || ds.isFakeNode()) {
                        continue;
                    }
                    getKeyVariablesForDataSource(service, ds, ds.getDbGroupConfig().getName(), keyVariablesTaskMap, needSync);
                }
            }
        }
        service.shutdown();
        int i = 0;
        while (!service.awaitTermination(100, TimeUnit.MILLISECONDS)) {
            if (LOGGER.isDebugEnabled()) {
                if (i == 0) {
                    LOGGER.debug("wait to get all dbInstances's get key variable");
                }
                i++;
                if (i == 100) { //log every 10 seconds
                    i = 0;
                }
            }
        }
    }

    private static void getAndSyncKeyVariablesForDataSources(Map<String, PhysicalDbGroup> dbGroups, Map<String, Future<KeyVariables>> keyVariablesTaskMap, boolean needSync) throws InterruptedException {
        ExecutorService service = Executors.newFixedThreadPool(dbGroups.size());
        for (Map.Entry<String, PhysicalDbGroup> entry : dbGroups.entrySet()) {
            String hostName = entry.getKey();
            PhysicalDbGroup pool = entry.getValue();

            for (PhysicalDbInstance ds : pool.getDbInstances(true)) {
                if (ds.isDisabled() || !ds.isTestConnSuccess() || ds.isFakeNode()) {
                    continue;
                }
                getKeyVariablesForDataSource(service, ds, hostName, keyVariablesTaskMap, needSync);
            }
        }
        service.shutdown();
        int i = 0;
        while (!service.awaitTermination(100, TimeUnit.MILLISECONDS)) {
            if (LOGGER.isDebugEnabled()) {
                if (i == 0) {
                    LOGGER.debug("wait to get all dbInstances's get key variable");
                }
                i++;
                if (i == 100) { //log every 10 seconds
                    i = 0;
                }
            }
        }
    }

    private static void getKeyVariablesForDataSource(ExecutorService service, PhysicalDbInstance ds, String hostName, Map<String, Future<KeyVariables>> keyVariablesTaskMap, boolean needSync) {
        String dataSourceName = genDataSourceKey(hostName, ds.getName());
        GetAndSyncDbInstanceKeyVariables task = new GetAndSyncDbInstanceKeyVariables(ds, needSync);
        Future<KeyVariables> future = service.submit(task);
        keyVariablesTaskMap.put(dataSourceName, future);
    }

    private static String genDataSourceKey(String hostName, String dsName) {
        return hostName + ":" + dsName;
    }
}
