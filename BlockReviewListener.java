package com.example.kyc.blocks;
import com.example.kyc.blocks.api.BlockReviewRequested;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BlockReviewListener {
  private final BlockService blocks;
  public BlockReviewListener(BlockService blocks){ this.blocks = blocks; }

  @ApplicationModuleListener @Transactional
  public void on(BlockReviewRequested e){
    blocks.ensureOpenVersion(e.partyId(), e.kind());
  }
}
