/*
 * This file is part of the OpenNMS(R) Application.
 *
 * OpenNMS(R) is Copyright (C) 2009 The OpenNMS Group, Inc.  All rights reserved.
 * OpenNMS(R) is a derivative work, containing both original code, included code and modified
 * code that was published under the GNU General Public License. Copyrights for modified
 * and included code are below.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * Modifications:
 * 
 * Created: January 27, 2009
 *
 * Copyright (C) 2009 The OpenNMS Group, Inc.  All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 *
 * For more information contact:
 *      OpenNMS Licensing       <license@opennms.org>
 *      http://www.opennms.org/
 *      http://www.opennms.com/
 */
package org.opennms.netmgt.ackd.readers;

import static org.junit.Assert.*;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import junit.framework.Assert;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.opennms.core.test.JUnitHttpServerExecutionListener;
import org.opennms.core.test.annotations.JUnitHttpServer;
import org.opennms.javamail.JavaMailerException;
import org.opennms.javamail.JavaSendMailer;
import org.opennms.netmgt.ackd.AckReader;
import org.opennms.netmgt.ackd.Ackd;
import org.opennms.netmgt.config.ackd.AckdConfiguration;
import org.opennms.netmgt.config.common.End2endMailConfig;
import org.opennms.netmgt.config.common.ReadmailConfig;
import org.opennms.netmgt.config.common.ReadmailHost;
import org.opennms.netmgt.config.common.ReadmailProtocol;
import org.opennms.netmgt.config.common.SendmailConfig;
import org.opennms.netmgt.config.common.SendmailHost;
import org.opennms.netmgt.config.common.SendmailMessage;
import org.opennms.netmgt.config.common.SendmailProtocol;
import org.opennms.netmgt.config.common.UserAuth;
import org.opennms.netmgt.dao.AckdConfigurationDao;
import org.opennms.netmgt.dao.JavaMailConfigurationDao;
import org.opennms.netmgt.dao.castor.DefaultAckdConfigurationDao;
import org.opennms.netmgt.dao.db.OpenNMSConfigurationExecutionListener;
import org.opennms.netmgt.dao.db.TemporaryDatabaseExecutionListener;
import org.opennms.netmgt.model.AckAction;
import org.opennms.netmgt.model.AckType;
import org.opennms.netmgt.model.OnmsAcknowledgment;
import org.opennms.netmgt.model.acknowledgments.AckService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;

@RunWith(SpringJUnit4ClassRunner.class)
@TestExecutionListeners({
    OpenNMSConfigurationExecutionListener.class,
    TemporaryDatabaseExecutionListener.class,
    DependencyInjectionTestExecutionListener.class,
    DirtiesContextTestExecutionListener.class,
    TransactionalTestExecutionListener.class,
    JUnitHttpServerExecutionListener.class
})
@ContextConfiguration(locations={
        "classpath:/META-INF/opennms/applicationContext-dao.xml",
        "classpath*:/META-INF/opennms/component-dao.xml",
        "classpath:/META-INF/opennms/applicationContext-daemon.xml",
        "classpath*:/META-INF/opennms/component-service.xml",
        "classpath:/META-INF/opennms/mockEventIpcManager.xml",
"classpath:/META-INF/opennms/applicationContext-ackd.xml" })

/**
 * Integration test of for the Javamail Acknowledgement Reader Implementation.
 */
public class HypericAckProcessorTest {

    @Autowired
    private Ackd m_daemon;

    // @Autowired
    // private AckReader m_reader;

    @Autowired
    private AckService m_ackService;


    @Test
    public void verifyWiring() {
        Assert.assertNotNull(m_ackService);
        Assert.assertNotNull(m_daemon);
    }

    private AckdConfigurationDao createAckdConfigDao() {

        class AckdConfigDao extends DefaultAckdConfigurationDao {

            public AckdConfiguration getConfig() {
                AckdConfiguration config = new AckdConfiguration();
                config.setAckExpression("~(?i)^AcK$");
                config.setAlarmidMatchExpression("~(?i).*alarmid:([0-9]+).*");
                config.setAlarmSync(true);
                config.setClearExpression("~(?i)^(Resolve|cleaR)$");
                config.setEscalateExpression("~(?i)^esc$");
                config.setNotifyidMatchExpression("~(?i).*RE:.*Notice #([0-9]+).*");
                config.setUnackExpression("~(?i)^unAck$");
                return config;
            }

        }

        return new AckdConfigDao();
    }

    @Ignore
    @Test
    @JUnitHttpServer(port=7081)
    public void testFetchHypericAlerts() throws Exception {
        // Test reading alerts over the HTTP server
        List<HypericAckProcessor.HypericAlertStatus> alerts = HypericAckProcessor.fetchHypericAlerts();
        assertEquals(5, alerts.size());
        for (HypericAckProcessor.HypericAlertStatus alert : alerts) {
            System.out.println(alert.toString());
        }
    }

    @Test
    public void testParseHypericAlerts() throws Exception {
        LineNumberReader reader = new LineNumberReader(new InputStreamReader(Thread.currentThread().getContextClassLoader().getResourceAsStream("hqu/opennms/alertStatus/list.hqu")));
        reader.mark(4000);
        try {
            while(true) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                } else {
                    System.out.println(line);
                }
            }
        } catch (IOException e) {
            // End of file
        }
        reader.reset();
        List<HypericAckProcessor.HypericAlertStatus> alerts = HypericAckProcessor.parseHypericAlerts(reader);
        assertEquals(5, alerts.size());
        for (HypericAckProcessor.HypericAlertStatus alert : alerts) {
            System.out.println(alert.toString());
        }
    }
}
