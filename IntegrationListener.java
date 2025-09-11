package com.example.kyc.integration;
import com.example.kyc.blocks.api.BlockVersionFinalized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class IntegrationListener {
  private static final Logger log = LoggerFactory.getLogger(IntegrationListener.class);

  @ApplicationModuleListener
  public void on(BlockVersionFinalized e){
    log.info("Integration stub: upsert block to Salesforce (party={}, kind={}, versionId={}, status={})",
      e.partyId(), e.kind(), e.blockVersionId(), e.finalStatus());
  }
}
