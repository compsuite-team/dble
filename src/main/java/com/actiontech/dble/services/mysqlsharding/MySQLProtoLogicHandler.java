/*
 * Copyright (C) 2016-2022 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.services.mysqlsharding;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.backend.mysql.MySQLMessage;
import com.actiontech.dble.config.ErrorCode;
import com.actiontech.dble.config.model.sharding.SchemaConfig;
import com.actiontech.dble.config.model.sharding.table.BaseTableConfig;
import com.actiontech.dble.net.mysql.OkPacket;
import com.actiontech.dble.route.RouteResultset;
import com.actiontech.dble.route.parser.druid.DruidParser;
import com.actiontech.dble.route.parser.druid.DruidParserFactory;
import com.actiontech.dble.route.parser.druid.ServerSchemaStatVisitor;
import com.actiontech.dble.route.parser.util.DruidUtil;
import com.actiontech.dble.server.response.FieldList;
import com.actiontech.dble.util.StringUtil;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.util.HashSet;
import java.util.Map;

/**
 * Created by szf on 2020/7/2.
 */
public class MySQLProtoLogicHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MySQLProtoLogicHandler.class);

    private final ShardingService service;

    private volatile byte[] multiQueryData = null;

    MySQLProtoLogicHandler(ShardingService service) {
        this.service = service;
    }


    public void initDB(byte[] data) {
        MySQLMessage mm = new MySQLMessage(data);
        mm.position(5);
        String db = null;
        try {
            db = mm.readString(service.getCharset().getClient());
        } catch (UnsupportedEncodingException e) {
            service.writeErrMessage(ErrorCode.ER_UNKNOWN_CHARACTER_SET, "Unknown charset '" + service.getCharset().getClient() + "'");
            return;
        }
        if (db != null && DbleServer.getInstance().getSystemVariables().isLowerCaseTableNames()) {
            db = db.toLowerCase();
        }
        // check sharding
        if (db == null || !DbleServer.getInstance().getConfig().getSchemas().containsKey(db)) {
            service.writeErrMessage(ErrorCode.ER_BAD_DB_ERROR, "Unknown database '" + db + "'");
            return;
        }
        if (!service.getUserConfig().getSchemas().contains(db)) {
            String s = "Access denied for user '" + service.getUser() + "' to database '" + db + "'";
            service.writeErrMessage(ErrorCode.ER_DBACCESS_DENIED_ERROR, s);
            return;
        }
        service.setSchema(db);
        service.getSession2().setRowCount(0);
        service.write(OkPacket.getDefault());
    }


    public void query(byte[] data) {
        this.multiQueryData = data;
        String sql;
        int position = 5;
        MySQLMessage mm;
        try {
            mm = new MySQLMessage(data);
            mm.position(position);
            String clientCharset = service.getCharset().getClient();
            sql = mm.readString(clientCharset);
            if (!StringUtil.byteEqual(data, sql.getBytes(clientCharset), position)) {
                mm = new MySQLMessage(data);
                mm.position(position);
                sql = getSql(sql, mm);
            }
        } catch (UnsupportedEncodingException e) {
            service.writeErrMessage(ErrorCode.ER_UNKNOWN_CHARACTER_SET, "Unknown charset '" + service.getCharset().getClient() + "'");
            return;
        }
        service.query(sql);
    }

    public String stmtPrepare(byte[] data) {
        MySQLMessage mm = new MySQLMessage(data);
        mm.position(5);
        String sql = null;
        try {
            sql = mm.readString(service.getCharset().getClient());
        } catch (UnsupportedEncodingException e) {
            service.writeErrMessage(ErrorCode.ER_UNKNOWN_CHARACTER_SET,
                    "Unknown charset '" + service.getCharset().getClient() + "'");
            return null;
        }
        if (sql == null || sql.length() == 0) {
            service.writeErrMessage(ErrorCode.ER_NOT_ALLOWED_COMMAND, "Empty SQL");
            return null;
        }
        return sql;
    }

    public void fieldList(byte[] data) {
        MySQLMessage mm = new MySQLMessage(data);
        mm.position(5);
        FieldList.response(service, mm.readStringWithNull());
    }

    public byte[] getMultiQueryData() {
        return multiQueryData;
    }


    private String getSql(String sql, MySQLMessage mm) {
        try {
            SQLStatement statement = DruidUtil.parseSQL(sql);
            SchemaConfig schemaConfig = DbleServer.getInstance().getConfig().getSchemas().get(service.getSchema());
            RouteResultset rrs = new RouteResultset(sql, -1);
            DruidParser druidParser = DruidParserFactory.create(statement, rrs.getSqlType(), service);
            ServerSchemaStatVisitor visitor = new ServerSchemaStatVisitor(schemaConfig.getName());
            druidParser.parser(schemaConfig, rrs, statement, visitor, service, true);
            Map<String, BaseTableConfig> tables = schemaConfig.getTables();
            HashSet<String> tableSet = Sets.newHashSet(visitor.getAliasMap().values());
            tableSet.add(visitor.getCurrentTable());
            boolean specifyCharset;
            for (String tableName : tableSet) {
                specifyCharset = tables.get(tableName).isSpecifyCharset();
                if (specifyCharset) {
                    sql = mm.readString(StringUtil.ISO_8859_1);
                    LOGGER.warn("Enforces {} to String, clientCharset sql is {}", StringUtil.ISO_8859_1, sql);
                    return sql;
                }
            }
        } catch (Exception e) {
            // ignore exception
            return sql;
        }
        return sql;
    }

}
