/*
* Copyright (C) 2016-2018 ActionTech.
* based on code by MyCATCopyrightHolder Copyright (c) 2013, OpenCloudDB/MyCAT.
* License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
*/
package com.actiontech.dble.backend.mysql.nio.handler;

import com.actiontech.dble.alarm.AlarmCode;
import com.actiontech.dble.alarm.Alert;
import com.actiontech.dble.alarm.AlertTask;
import com.actiontech.dble.alarm.AlertUtil;
import com.actiontech.dble.backend.BackendConnection;
import com.actiontech.dble.backend.mysql.CharsetUtil;
import com.actiontech.dble.backend.mysql.nio.MySQLConnection;
import com.actiontech.dble.net.mysql.*;
import com.actiontech.dble.server.NonBlockingSession;
import com.actiontech.dble.server.status.AlertManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.util.List;

/**
 * @author mycat
 */
public class KillConnectionHandler implements ResponseHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(KillConnectionHandler.class);

    private final MySQLConnection toKilled;
    private final NonBlockingSession session;

    public KillConnectionHandler(BackendConnection toKilled,
                                 NonBlockingSession session) {
        this.toKilled = (MySQLConnection) toKilled;
        this.session = session;
    }

    @Override
    public void connectionAcquired(BackendConnection conn) {
        conn.setResponseHandler(this);
        conn.setSession(session);
        ((MySQLConnection) conn).sendQueryCmd(("KILL " + toKilled.getThreadId()), session.getSource().getCharset());
    }

    @Override
    public void connectionError(final Throwable e, final BackendConnection conn) {
        if (conn != null) {
            AlertManager.getInstance().getAlertQueue().offer(new AlertTask() {
                @Override
                public void send() {
                    AlertUtil.alertSelf(AlarmCode.KILL_BACKEND_CONN_FAIL, Alert.AlertLevel.NOTICE, "get killer connection " + conn.toString() + " failed:" + e.getMessage(), null);
                }

                @Override
                public String toString() {
                    return "AlertManager Task alertSelf " + AlarmCode.KILL_BACKEND_CONN_FAIL + " get killer connection " + conn.toString() + " failed:" + e.getMessage();
                }
            });
            toKilled.close("exception:" + e.toString());
        }
    }

    @Override
    public void okResponse(byte[] ok, BackendConnection conn) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("kill connection success connection id:" +
                    toKilled.getThreadId());
        }
        conn.release();
        toKilled.close("killed");

    }

    @Override
    public void rowEofResponse(byte[] eof, boolean isLeft, BackendConnection conn) {
        LOGGER.info("unexpected packet for " +
                conn + " bound by " + session.getSource() +
                ": field's eof");
        conn.close("close unexpected packet of killConnection");
        toKilled.close("killed");
    }

    @Override
    public void errorResponse(byte[] data, BackendConnection conn) {
        ErrorPacket err = new ErrorPacket();
        err.read(data);
        String msg = null;
        try {
            msg = new String(err.getMessage(), CharsetUtil.getJavaCharset(conn.getCharset().getResults()));
        } catch (UnsupportedEncodingException e) {
            msg = new String(err.getMessage());
        }
        LOGGER.info("kill backend connection " + toKilled + " failed: " + msg + " con:" + conn);
        final String connString = conn.toString();
        final String msgx = msg;
        AlertManager.getInstance().getAlertQueue().offer(new AlertTask() {
            @Override
            public void send() {
                AlertUtil.alertSelf(AlarmCode.KILL_BACKEND_CONN_FAIL, Alert.AlertLevel.NOTICE, "get killer connection " + connString + " failed: " + msgx, null);
            }

            @Override
            public String toString() {
                return "AlertManager Task alertSelf " + AlarmCode.KILL_BACKEND_CONN_FAIL + " get killer connection " + connString + " failed: " + msgx;
            }
        });
        conn.release();
        toKilled.close("exception:" + msg);
    }

    @Override
    public void fieldEofResponse(byte[] header, List<byte[]> fields, List<FieldPacket> fieldPackets, byte[] eof,
                                 boolean isLeft, BackendConnection conn) {
    }

    @Override
    public boolean rowResponse(byte[] row, RowDataPacket rowPacket, boolean isLeft, BackendConnection conn) {
        return false;
    }

    @Override
    public void writeQueueAvailable() {

    }

    @Override
    public void connectionClose(final BackendConnection conn, String reason) {
        AlertManager.getInstance().getAlertQueue().offer(new AlertTask() {
            @Override
            public void send() {
                AlertUtil.alertSelf(AlarmCode.KILL_BACKEND_CONN_FAIL, Alert.AlertLevel.NOTICE, "get killer connection " + conn.toString() + " failed: connectionClosed", null);
            }

            @Override
            public String toString() {
                return "AlertManager Task alertSelf " + AlarmCode.KILL_BACKEND_CONN_FAIL + " get killer connection " + conn.toString() + " failed: connectionClosed";
            }
        });
        toKilled.close("exception:" + reason);
    }
}
