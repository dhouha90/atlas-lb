package org.openstack.atlas.api.async;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openstack.atlas.service.domain.entities.LoadBalancer;
import org.openstack.atlas.service.domain.entities.LoadBalancerStatus;
import org.openstack.atlas.service.domain.exceptions.EntityNotFoundException;
import org.openstack.atlas.service.domain.pojos.MessageDataContainer;

import javax.jms.Message;

import static org.openstack.atlas.service.domain.events.entities.CategoryType.DELETE;
import static org.openstack.atlas.service.domain.events.entities.EventSeverity.CRITICAL;
import static org.openstack.atlas.service.domain.events.entities.EventType.DELETE_CERTIFICATE_MAPPING;
import static org.openstack.atlas.service.domain.services.helpers.AlertType.DATABASE_FAILURE;
import static org.openstack.atlas.service.domain.services.helpers.AlertType.ZEUS_FAILURE;

public class DeleteCertificateMappingListener extends BaseListener {
    private final Log LOG = LogFactory.getLog(DeleteCertificateMappingListener.class);

    @Override
    public void doOnMessage(Message message) throws Exception {
        LOG.debug("Entering " + getClass());
        LOG.debug(message);

        MessageDataContainer dataContainer = getDataContainerFromMessage(message);
        LoadBalancer dbLoadBalancer;

        try {
            dbLoadBalancer = loadBalancerService.get(dataContainer.getLoadBalancerId(), dataContainer.getAccountId());
        } catch (EntityNotFoundException enfe) {
            String alertDescription = String.format("Load balancer '%d' not found in database.", dataContainer.getLoadBalancerId());
            LOG.error(alertDescription, enfe);
            notificationService.saveAlert(dataContainer.getAccountId(), dataContainer.getLoadBalancerId(), enfe, DATABASE_FAILURE.name(), alertDescription);
            sendErrorToEventResource(dataContainer);
            return;
        }

        try {
            LOG.debug(String.format("Removing certificate mapping '%d' from load balancer '%d' in ZXTM...", dataContainer.getCertificateMappingId(), dataContainer.getLoadBalancerId()));
            reverseProxyLoadBalancerService.removeCertificateMapping(dataContainer.getLoadBalancerId(), dataContainer.getAccountId(), dataContainer.getCertificateMappingId());
            LOG.debug(String.format("Successfully removed certificate mapping '%d' from load balancer '%d' in Zeus.", dataContainer.getCertificateMappingId(), dataContainer.getLoadBalancerId()));
        } catch (Exception e) {
            loadBalancerService.setStatus(dbLoadBalancer, LoadBalancerStatus.ERROR);
            String alertDescription = String.format("Error removing certificate mapping '%d' in Zeus for loadbalancer '%d'.", dataContainer.getCertificateMappingId(), dataContainer.getLoadBalancerId());
            LOG.error(alertDescription, e);
            notificationService.saveAlert(dataContainer.getAccountId(), dataContainer.getLoadBalancerId(), e, ZEUS_FAILURE.name(), alertDescription);
            sendErrorToEventResource(dataContainer);
            return;
        }

        // Remove the certificate mapping from the database
        certificateMappingService.deleteByIdAndLoadBalancerId(dataContainer.getCertificateMappingId(), dataContainer.getLoadBalancerId());
        // Update load balancer status in database
        loadBalancerService.setStatus(dbLoadBalancer, LoadBalancerStatus.ACTIVE);
        // Save a load balancer status record
        loadBalancerStatusHistoryService.save(dataContainer.getAccountId(), dataContainer.getLoadBalancerId(), LoadBalancerStatus.ACTIVE);

        LOG.info(String.format("Delete certificate mapping operation complete for load balancer '%d' with certificate mapping '%d'.", dataContainer.getLoadBalancerId(), dataContainer.getCertificateMappingId()));
    }

    private void sendErrorToEventResource(MessageDataContainer dataContainer) {
        String title = "Error Deleting Certificate Mapping";
        String desc = "Could not delete the certificate mapping at this time.";
        notificationService.saveLoadBalancerEvent(dataContainer.getUserName(), dataContainer.getAccountId(), dataContainer.getLoadBalancerId(), title, desc, DELETE_CERTIFICATE_MAPPING, DELETE, CRITICAL);
    }
}
