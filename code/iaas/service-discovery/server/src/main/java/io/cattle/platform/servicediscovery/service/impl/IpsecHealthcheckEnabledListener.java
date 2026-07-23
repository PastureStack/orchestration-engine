package io.cattle.platform.servicediscovery.service.impl;

import static io.cattle.platform.core.model.tables.AccountTable.*;
import io.cattle.platform.agent.instance.service.AgentMetadataService;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.util.type.InitializationTask;
import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.condition.ConditionType;

import java.util.List;

import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class IpsecHealthcheckEnabledListener implements InitializationTask {
  private static final ConfigProperty<Boolean> ENABLE_HEALTHCHECK = ArchaiusUtil.getBooleanProperty("ipsec.service.enable.healthcheck");
  private static final Logger logger = LoggerFactory.getLogger(IpsecHealthcheckEnabledListener.class);
  
  @Inject
  ObjectManager objectManager;
  @Inject
  AgentMetadataService agentMetadata;
      
  @Override
  public void start() {
    Runnable updateMetadata = new Runnable() {
        public void run() {
          logger.info("ipsec.service.enable.healthcheck changed");
          List<Account> accounts = objectManager.find(Account.class, ACCOUNT.KIND, AccountConstants.PROJECT_KIND, 
              ACCOUNT.REMOVED, new Condition(ConditionType.NULL));
          for(Account account : accounts) {
            agentMetadata.updateMetadata(account.getId());
          }
        }
      };
    ENABLE_HEALTHCHECK.addCallback(updateMetadata);
  }
}
