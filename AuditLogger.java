package com.example.kyc.audit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AuditLogger {
  private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);
  @EventListener
  public void on(Object evt){
    log.debug("AUDIT event: {}", evt.getClass().getName());
  }
}
