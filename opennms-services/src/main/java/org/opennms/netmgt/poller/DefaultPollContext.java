//
// This file is part of the OpenNMS(R) Application.
//
// OpenNMS(R) is Copyright (C) 2005 The OpenNMS Group, Inc.  All rights reserved.
// OpenNMS(R) is a derivative work, containing both original code, included code and modified
// code that was published under the GNU General Public License. Copyrights for modified 
// and included code are below.
//
// OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
//
// Original code base Copyright (C) 1999-2001 Oculan Corp.  All rights reserved.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
//
// For more information contact:
// OpenNMS Licensing       <license@opennms.org>
//     http://www.opennms.org/
//     http://www.opennms.com/
//
package org.opennms.netmgt.poller;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Category;
import org.opennms.core.utils.ThreadCategory;
import org.opennms.netmgt.EventConstants;
import org.opennms.netmgt.capsd.plugins.IcmpPlugin;
import org.opennms.netmgt.config.OpennmsServerConfigFactory;
import org.opennms.netmgt.config.PollerConfig;
import org.opennms.netmgt.eventd.EventIpcManager;
import org.opennms.netmgt.model.events.EventBuilder;
import org.opennms.netmgt.model.events.EventListener;
import org.opennms.netmgt.poller.pollables.PendingPollEvent;
import org.opennms.netmgt.poller.pollables.PollContext;
import org.opennms.netmgt.poller.pollables.PollEvent;
import org.opennms.netmgt.poller.pollables.PollableService;
import org.opennms.netmgt.xml.event.Event;

/**
 * Represents a DefaultPollContext 
 *
 * @author brozow
 */
public class DefaultPollContext implements PollContext, EventListener {
    
    private PollerConfig m_pollerConfig;
    private QueryManager m_queryManager;
    private EventIpcManager m_eventManager;
    private String m_name;
    private String m_localHostName;
    private boolean m_listenerAdded = false;
    private List<PendingPollEvent> m_pendingPollEvents = new LinkedList<PendingPollEvent>();

    public EventIpcManager getEventManager() {
        return m_eventManager;
    }
    
    public void setEventManager(EventIpcManager eventManager) {
        m_eventManager = eventManager;
    }
    
    public void setLocalHostName(String localHostName) {
        m_localHostName = localHostName;
    }
    
    public String getLocalHostName() {
        return m_localHostName;
    }

    public String getName() {
        return m_name;
    }

    public void setName(String name) {
        m_name = name;
    }

    public PollerConfig getPollerConfig() {
        return m_pollerConfig;
    }

    public void setPollerConfig(PollerConfig pollerConfig) {
        m_pollerConfig = pollerConfig;
    }

    public QueryManager getQueryManager() {
        return m_queryManager;
    }

    public void setQueryManager(QueryManager queryManager) {
        m_queryManager = queryManager;
    }

    /* (non-Javadoc)
     * @see org.opennms.netmgt.poller.pollables.PollContext#getCriticalServiceName()
     */
    public String getCriticalServiceName() {
        return getPollerConfig().getCriticalService();
    }

    /* (non-Javadoc)
     * @see org.opennms.netmgt.poller.pollables.PollContext#isNodeProcessingEnabled()
     */
    public boolean isNodeProcessingEnabled() {
        return getPollerConfig().nodeOutageProcessingEnabled();
    }

    /* (non-Javadoc)
     * @see org.opennms.netmgt.poller.pollables.PollContext#isPollingAllIfCritServiceUndefined()
     */
    public boolean isPollingAllIfCritServiceUndefined() {
        return getPollerConfig().pollAllIfNoCriticalServiceDefined();
    }

    /* (non-Javadoc)
     * @see org.opennms.netmgt.poller.pollables.PollContext#sendEvent(org.opennms.netmgt.xml.event.Event)
     */
    public PollEvent sendEvent(Event event) {
        if (!m_listenerAdded) {
            getEventManager().addEventListener(this);
            m_listenerAdded = true;
        }
        PendingPollEvent pollEvent = new PendingPollEvent(event);
        synchronized (m_pendingPollEvents) {
            m_pendingPollEvents.add(pollEvent);
        }
        //log().info("Sending "+event.getUei()+" for element "+event.getNodeid()+":"+event.getInterface()+":"+event.getService(), new Exception("StackTrace"));
        getEventManager().sendNow(event);
        return pollEvent;
    }

    Category log() {
        return ThreadCategory.getInstance(getClass());
    }

    /* (non-Javadoc)
     * @see org.opennms.netmgt.poller.pollables.PollContext#createEvent(java.lang.String, int, java.net.InetAddress, java.lang.String, java.util.Date)
     */
    public Event createEvent(String uei, int nodeId, InetAddress address, String svcName, Date date, String reason) {
        Category log = ThreadCategory.getInstance(this.getClass());
        
        if (log.isDebugEnabled())
            log.debug("createEvent: uei = " + uei + " nodeid = " + nodeId);
        
        EventBuilder bldr = new EventBuilder(uei, this.getName(), date);
        bldr.setNodeid(nodeId);
        if (address != null) {
            bldr.setInterface(address.getHostAddress());
        }
        if (svcName != null) {
            bldr.setService(svcName);
        }
        bldr.setHost(this.getLocalHostName());
        
        if (uei.equals(EventConstants.NODE_DOWN_EVENT_UEI)
                && this.getPollerConfig().pathOutageEnabled()) {
            String[] criticalPath = this.getQueryManager().getCriticalPath(nodeId);
            
            if (criticalPath[0] != null && !criticalPath[0].equals("")) {
                if (!this.testCriticalPath(criticalPath)) {
                    log.debug("Critical path test failed for node " + nodeId);
                    
                    // add eventReason, criticalPathIp, criticalPathService
                    // parms
                    
                    bldr.addParam(EventConstants.PARM_LOSTSERVICE_REASON, EventConstants.PARM_VALUE_PATHOUTAGE);
                    bldr.addParam(EventConstants.PARM_CRITICAL_PATH_IP, criticalPath[0]);
                    bldr.addParam(EventConstants.PARM_CRITICAL_PATH_SVC, criticalPath[1]);
                    
                } else {
                    log.debug("Critical path test passed for node " + nodeId);
                }
            } else {
                log.debug("No Critical path to test for node " + nodeId);
            }
        }
        
        if (uei.equals(EventConstants.NODE_LOST_SERVICE_EVENT_UEI)) {
            bldr.addParam(EventConstants.PARM_LOCATION_MONITOR_ID, (reason == null ? "Unknown" : reason));
        }
        
        // For node level events (nodeUp/nodeDown) retrieve the
        // node's nodeLabel value and add it as a parm
        if (uei.equals(EventConstants.NODE_UP_EVENT_UEI)
                || uei.equals(EventConstants.NODE_DOWN_EVENT_UEI)) {
        
            String nodeLabel = this.getNodeLabel(nodeId);
            bldr.addParam(EventConstants.PARM_NODE_LABEL, nodeLabel);
            
        }
        
        return bldr.getEvent();
    }

    public void openOutage(final PollableService svc, final PollEvent svcLostEvent) {
        log().debug("openOutage: Opening outage for: "+svc+" with event:"+svcLostEvent);
        final int nodeId = svc.getNodeId();
        final String ipAddr = svc.getIpAddr();
        final String svcName = svc.getSvcName();
        Runnable r = new Runnable() {
            public void run() {
                log().debug("run: Opening outage with query manager: "+svc+" with event:"+svcLostEvent);
                getQueryManager().openOutage(getPollerConfig().getNextOutageIdSql(), nodeId, ipAddr, svcName, svcLostEvent.getEventId(), EventConstants.formatToString(svcLostEvent.getDate()));
            }

        };
        if (svcLostEvent instanceof PendingPollEvent) {
            ((PendingPollEvent)svcLostEvent).addPending(r);
        }
        else {
            r.run();
        }
        
    }

    /* (non-Javadoc)
     * @see org.opennms.netmgt.poller.pollables.PollContext#resolveOutage(org.opennms.netmgt.poller.pollables.PollableService, org.opennms.netmgt.xml.event.Event)
     */
    public void resolveOutage(PollableService svc, final PollEvent svcRegainEvent) {
        final int nodeId = svc.getNodeId();
        final String ipAddr = svc.getIpAddr();
        final String svcName = svc.getSvcName();
        Runnable r = new Runnable() {
            public void run() {
                getQueryManager().resolveOutage(nodeId, ipAddr, svcName, svcRegainEvent.getEventId(), EventConstants.formatToString(svcRegainEvent.getDate()));
            }
        };
        if (svcRegainEvent instanceof PendingPollEvent) {
            ((PendingPollEvent)svcRegainEvent).addPending(r);
        }
        else {
            r.run();
        }
    }
    
    public void reparentOutages(String ipAddr, int oldNodeId, int newNodeId) {
        getQueryManager().reparentOutages(ipAddr, oldNodeId, newNodeId);
    }

    /* (non-Javadoc)
     * @see org.opennms.netmgt.poller.pollables.PollContext#isServiceUnresponsiveEnabled()
     */
    public boolean isServiceUnresponsiveEnabled() {
        return getPollerConfig().serviceUnresponsiveEnabled();
    }

    /* (non-Javadoc)
     * @see org.opennms.netmgt.eventd.EventListener#onEvent(org.opennms.netmgt.xml.event.Event)
     */
    public void onEvent(Event e) {
        synchronized (m_pendingPollEvents) {
            log().debug("onEvent: Received event: "+e+" uei: "+e.getUei()+", dbid: "+e.getDbid());
            for (Iterator<PendingPollEvent> it = m_pendingPollEvents .iterator(); it.hasNext();) {
                PendingPollEvent pollEvent = it.next();
                log().debug("onEvent: comparing events to poll event: "+pollEvent);
                if (e.equals(pollEvent.getEvent())) {
                    log().debug("onEvent: completing pollevent: "+pollEvent);
                    pollEvent.complete(e);
                }
            }
            
            for (Iterator<PendingPollEvent> it = m_pendingPollEvents.iterator(); it.hasNext(); ) {
                PendingPollEvent pollEvent = it.next();
                log().debug("onEvent: determining if pollEvent is pending: "+pollEvent);
                if (pollEvent.isPending()) continue;
                
                log().debug("onEvent: processing pending pollEvent...: "+pollEvent);
                pollEvent.processPending();
                it.remove();
                log().debug("onEvent: processing of pollEvent completed.: "+pollEvent);
            }
        }
        
    }

    boolean testCriticalPath(String[] criticalPath) {
        // TODO: Generalize the service
        InetAddress addr = null;
        boolean result = true;
    
        Category log = log();
        log.debug("Test critical path IP " + criticalPath[0]);
        try {
            addr = InetAddress.getByName(criticalPath[0]);
        } catch (UnknownHostException e) {
            log.error(
                        "failed to convert string address to InetAddress "
                                + criticalPath[0]);
            return true;
        }
        try {
            IcmpPlugin p = new IcmpPlugin();
            Map<String, Object> map = new HashMap<String, Object>();
            map.put(
                    "retry",
                    new Long(
                             OpennmsServerConfigFactory.getInstance().getDefaultCriticalPathRetries()));
            map.put(
                    "timeout",
                    new Long(
                             OpennmsServerConfigFactory.getInstance().getDefaultCriticalPathTimeout()));
    
            result = p.isProtocolSupported(addr, map);
        } catch (IOException e) {
            log.error("IOException when testing critical path " + e);
        }
        return result;
    }

    String getNodeLabel(int nodeId) {
        String nodeLabel = null;
        try {
            nodeLabel = getQueryManager().getNodeLabel(nodeId);
        } catch (SQLException sqlE) {
            // Log a warning
            log().warn("Failed to retrieve node label for nodeid " + nodeId,
                     sqlE);
        }
    
        if (nodeLabel == null) {
            // This should never happen but if it does just
            // use nodeId for the nodeLabel so that the
            // event description has something to display.
            nodeLabel = String.valueOf(nodeId);
        }
        return nodeLabel;
    }

}
